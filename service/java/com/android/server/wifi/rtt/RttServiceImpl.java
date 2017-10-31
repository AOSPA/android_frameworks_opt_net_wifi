/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi.rtt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.wifi.V1_0.RttResult;
import android.hardware.wifi.V1_0.RttStatus;
import android.net.wifi.ScanResult;
import android.net.wifi.aware.IWifiAwareMacAddressProvider;
import android.net.wifi.aware.IWifiAwareManager;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.rtt.IRttCallback;
import android.net.wifi.rtt.IWifiRttManager;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.RangingResultCallback;
import android.net.wifi.rtt.WifiRttManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.WakeupMessage;
import com.android.server.wifi.util.NativeUtil;
import com.android.server.wifi.util.WifiPermissionsUtil;

import libcore.util.HexEncoding;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

/**
 * Implementation of the IWifiRttManager AIDL interface and of the RttService state manager.
 */
public class RttServiceImpl extends IWifiRttManager.Stub {
    private static final String TAG = "RttServiceImpl";
    private static final boolean VDBG = true; // STOPSHIP if true

    private final Context mContext;
    private IWifiAwareManager mAwareBinder;
    private RttNative mRttNative;
    private WifiPermissionsUtil mWifiPermissionsUtil;
    private PowerManager mPowerManager;

    private RttServiceSynchronized mRttServiceSynchronized;

    @VisibleForTesting
    public static final String HAL_RANGING_TIMEOUT_TAG = TAG + " HAL Ranging Timeout";

    private static final long HAL_RANGING_TIMEOUT_MS = 5_000;

    public RttServiceImpl(Context context) {
        mContext = context;
    }

    /*
     * INITIALIZATION
     */

    /**
     * Initializes the RTT service (usually with objects from an injector).
     *
     * @param looper The looper on which to synchronize operations.
     * @param awareBinder The Wi-Fi Aware service (binder) if supported on the system.
     * @param rttNative The Native interface to the HAL.
     * @param wifiPermissionsUtil Utility for permission checks.
     */
    public void start(Looper looper, IWifiAwareManager awareBinder, RttNative rttNative,
            WifiPermissionsUtil wifiPermissionsUtil) {
        mAwareBinder = awareBinder;
        mRttNative = rttNative;
        mWifiPermissionsUtil = wifiPermissionsUtil;
        mRttServiceSynchronized = new RttServiceSynchronized(looper, rttNative);

        mPowerManager = mContext.getSystemService(PowerManager.class);
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (VDBG) Log.v(TAG, "BroadcastReceiver: action=" + action);

                if (PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED.equals(action)) {
                    if (mPowerManager.isDeviceIdleMode()) {
                        disable();
                    } else {
                        enable();
                    }
                }
            }
        }, intentFilter);
    }

    /*
     * ASYNCHRONOUS DOMAIN - can be called from different threads!
     */

    /**
     * Proxy for the final native call of the parent class. Enables mocking of
     * the function.
     */
    public int getMockableCallingUid() {
        return getCallingUid();
    }

    /**
     * Enable the API: broadcast notification
     */
    public void enable() {
        if (VDBG) Log.v(TAG, "enable");
        sendRttStateChangedBroadcast(true);
        mRttServiceSynchronized.mHandler.post(() -> {
            // queue should be empty at this point (but this call allows validation)
            mRttServiceSynchronized.executeNextRangingRequestIfPossible(false);
        });
    }

    /**
     * Disable the API:
     * - Clean-up (fail) pending requests
     * - Broadcast notification
     */
    public void disable() {
        if (VDBG) Log.v(TAG, "disable");
        sendRttStateChangedBroadcast(false);
        mRttServiceSynchronized.mHandler.post(() -> {
            mRttServiceSynchronized.cleanUpOnDisable();
        });
    }

    /**
     * Binder interface API to indicate whether the API is currently available. This requires an
     * immediate asynchronous response.
     */
    @Override
    public boolean isAvailable() {
        return mRttNative.isReady() && !mPowerManager.isDeviceIdleMode();
    }

    /**
     * Binder interface API to start a ranging operation. Called on binder thread, operations needs
     * to be posted to handler thread.
     */
    @Override
    public void startRanging(IBinder binder, String callingPackage, RangingRequest request,
            IRttCallback callback) throws RemoteException {
        if (VDBG) {
            Log.v(TAG, "startRanging: binder=" + binder + ", callingPackage=" + callingPackage
                    + ", request=" + request + ", callback=" + callback);
        }
        // verify arguments
        if (binder == null) {
            throw new IllegalArgumentException("Binder must not be null");
        }
        if (request == null || request.mRttPeers == null || request.mRttPeers.size() == 0) {
            throw new IllegalArgumentException("Request must not be null or empty");
        }
        if (callback == null) {
            throw new IllegalArgumentException("Callback must not be null");
        }
        request.enforceValidity(mAwareBinder != null);

        if (!isAvailable()) {
            try {
                callback.onRangingFailure(RangingResultCallback.STATUS_CODE_FAIL_RTT_NOT_AVAILABLE);
            } catch (RemoteException e) {
                Log.e(TAG, "startRanging: disabled, callback failed -- " + e);
            }
            return;
        }

        final int uid = getMockableCallingUid();

        // permission check
        enforceAccessPermission();
        enforceChangePermission();
        enforceLocationPermission(callingPackage, uid);

        // register for binder death
        IBinder.DeathRecipient dr = new IBinder.DeathRecipient() {
            @Override
            public void binderDied() {
                if (VDBG) Log.v(TAG, "binderDied: uid=" + uid);
                binder.unlinkToDeath(this, 0);

                mRttServiceSynchronized.mHandler.post(() -> {
                    mRttServiceSynchronized.cleanUpOnClientDeath(uid);
                });
            }
        };

        try {
            binder.linkToDeath(dr, 0);
        } catch (RemoteException e) {
            Log.e(TAG, "Error on linkToDeath - " + e);
        }

        mRttServiceSynchronized.mHandler.post(() -> {
            mRttServiceSynchronized.queueRangingRequest(uid, binder, dr, callingPackage, request,
                    callback);
        });
    }

    /**
     * Called by HAL to report ranging results. Called on HAL thread - needs to post to local
     * thread.
     */
    public void onRangingResults(int cmdId, List<RttResult> results) {
        if (VDBG) Log.v(TAG, "onRangingResults: cmdId=" + cmdId);
        mRttServiceSynchronized.mHandler.post(() -> {
            mRttServiceSynchronized.onRangingResults(cmdId, results);
        });
    }

    private void enforceAccessPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.ACCESS_WIFI_STATE, TAG);
    }

    private void enforceChangePermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CHANGE_WIFI_STATE, TAG);
    }

    private void enforceLocationPermission(String callingPackage, int uid) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION,
                TAG);
    }

    private void sendRttStateChangedBroadcast(boolean enabled) {
        if (VDBG) Log.v(TAG, "sendRttStateChangedBroadcast: enabled=" + enabled);
        final Intent intent = new Intent(WifiRttManager.ACTION_WIFI_RTT_STATE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump RttService from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid());
            return;
        }
        pw.println("Wi-Fi RTT Service");
        mRttServiceSynchronized.dump(fd, pw, args);
    }

    /*
     * SYNCHRONIZED DOMAIN
     */

    /**
     * RTT service implementation - synchronized on a single thread. All commands should be posted
     * to the exposed handler.
     */
    private class RttServiceSynchronized {
        public Handler mHandler;

        private RttNative mRttNative;
        private int mNextCommandId = 1000;
        private List<RttRequestInfo> mRttRequestQueue = new LinkedList<>();
        private WakeupMessage mRangingTimeoutMessage = null;

        RttServiceSynchronized(Looper looper, RttNative rttNative) {
            mRttNative = rttNative;

            mHandler = new Handler(looper);
            mRangingTimeoutMessage = new WakeupMessage(mContext, mHandler,
                    HAL_RANGING_TIMEOUT_TAG, () -> {
                timeoutRangingRequest();
            });
        }

        private void cancelRanging(RttRequestInfo rri) {
            ArrayList<byte[]> macAddresses = new ArrayList<>();
            for (RangingRequest.RttPeer peer : rri.request.mRttPeers) {
                if (peer instanceof RangingRequest.RttPeerAp) {
                    ScanResult scanResult =
                            ((RangingRequest.RttPeerAp) peer).scanResult;

                    byte[] addr = NativeUtil.macAddressToByteArray(scanResult.BSSID);
                    if (addr.length != 6) {
                        Log.e(TAG, "Invalid configuration: unexpected BSSID length -- "
                                + peer);
                        continue;
                    }
                    macAddresses.add(addr);
                } else if (peer instanceof RangingRequest.RttPeerAware) {
                    if (((RangingRequest.RttPeerAware) peer).peerMacAddress != null) {
                        macAddresses.add(
                                ((RangingRequest.RttPeerAware) peer).peerMacAddress);
                    }
                }
            }

            mRttNative.rangeCancel(rri.cmdId, macAddresses);
        }

        private void cleanUpOnDisable() {
            if (VDBG) Log.v(TAG, "RttServiceSynchronized.cleanUpOnDisable");
            for (RttRequestInfo rri : mRttRequestQueue) {
                try {
                    if (rri.dispatchedToNative) {
                        // may not be necessary in some cases (e.g. Wi-Fi disable may already clear
                        // up active RTT), but in other cases will be needed (doze disabling RTT
                        // but Wi-Fi still up). Doesn't hurt - worst case will fail.
                        cancelRanging(rri);
                    }
                    rri.callback.onRangingFailure(
                            RangingResultCallback.STATUS_CODE_FAIL_RTT_NOT_AVAILABLE);
                } catch (RemoteException e) {
                    Log.e(TAG, "RttServiceSynchronized.startRanging: disabled, callback failed -- "
                            + e);
                }
            }
            mRttRequestQueue.clear();
            mRangingTimeoutMessage.cancel();
        }

        private void cleanUpOnClientDeath(int uid) {
            if (VDBG) {
                Log.v(TAG, "RttServiceSynchronized.cleanUpOnClientDeath: uid=" + uid
                        + ", mRttRequestQueue=" + mRttRequestQueue);
            }
            ListIterator<RttRequestInfo> it = mRttRequestQueue.listIterator();
            while (it.hasNext()) {
                RttRequestInfo rri = it.next();
                if (rri.uid == uid) {
                    if (!rri.dispatchedToNative) {
                        it.remove();
                    } else {
                        Log.d(TAG, "Client death - cancelling RTT operation in progress: cmdId="
                                + rri.cmdId);
                        mRangingTimeoutMessage.cancel();
                        cancelRanging(rri);
                    }
                }
            }

            if (VDBG) {
                Log.v(TAG, "RttServiceSynchronized.cleanUpOnClientDeath: uid=" + uid
                        + ", after cleanup - mRttRequestQueue=" + mRttRequestQueue);
            }
        }

        private void timeoutRangingRequest() {
            if (VDBG) {
                Log.v(TAG, "RttServiceSynchronized.timeoutRangingRequest mRttRequestQueue="
                        + mRttRequestQueue);
            }
            if (mRttRequestQueue.size() == 0) {
                Log.w(TAG, "RttServiceSynchronized.timeoutRangingRequest: but nothing in queue!?");
                return;
            }
            RttRequestInfo rri = mRttRequestQueue.get(0);
            if (!rri.dispatchedToNative) {
                Log.w(TAG, "RttServiceSynchronized.timeoutRangingRequest: command not dispatched "
                        + "to native!?");
                return;
            }
            cancelRanging(rri);
            try {
                rri.callback.onRangingFailure(RangingResultCallback.STATUS_CODE_FAIL);
            } catch (RemoteException e) {
                Log.e(TAG, "RttServiceSynchronized.timeoutRangingRequest: callback failed: " + e);
            }
            executeNextRangingRequestIfPossible(true);
        }

        private void queueRangingRequest(int uid, IBinder binder, IBinder.DeathRecipient dr,
                String callingPackage, RangingRequest request, IRttCallback callback) {
            RttRequestInfo newRequest = new RttRequestInfo();
            newRequest.uid = uid;
            newRequest.binder = binder;
            newRequest.dr = dr;
            newRequest.callingPackage = callingPackage;
            newRequest.request = request;
            newRequest.callback = callback;
            mRttRequestQueue.add(newRequest);

            if (VDBG) {
                Log.v(TAG, "RttServiceSynchronized.queueRangingRequest: newRequest=" + newRequest);
            }

            executeNextRangingRequestIfPossible(false);
        }

        private void executeNextRangingRequestIfPossible(boolean popFirst) {
            if (VDBG) Log.v(TAG, "executeNextRangingRequestIfPossible: popFirst=" + popFirst);

            if (popFirst) {
                if (mRttRequestQueue.size() == 0) {
                    Log.w(TAG, "executeNextRangingRequestIfPossible: pop requested - but empty "
                            + "queue!? Ignoring pop.");
                } else {
                    mRttRequestQueue.remove(0);
                }
            }

            if (mRttRequestQueue.size() == 0) {
                if (VDBG) Log.v(TAG, "executeNextRangingRequestIfPossible: no requests pending");
                return;
            }

            RttRequestInfo nextRequest = mRttRequestQueue.get(0);
            if (nextRequest.peerHandlesTranslated || nextRequest.dispatchedToNative) {
                if (VDBG) {
                    Log.v(TAG, "executeNextRangingRequestIfPossible: called but a command is "
                            + "executing. topOfQueue=" + nextRequest);
                }
                return;
            }

            startRanging(nextRequest);
        }

        private void startRanging(RttRequestInfo nextRequest) {
            if (VDBG) {
                Log.v(TAG, "RttServiceSynchronized.startRanging: nextRequest=" + nextRequest);
            }

            if (!isAvailable()) {
                Log.d(TAG, "RttServiceSynchronized.startRanging: disabled");
                try {
                    nextRequest.callback.onRangingFailure(
                            RangingResultCallback.STATUS_CODE_FAIL_RTT_NOT_AVAILABLE);
                } catch (RemoteException e) {
                    Log.e(TAG, "RttServiceSynchronized.startRanging: disabled, callback failed -- "
                            + e);
                    executeNextRangingRequestIfPossible(true);
                    return;
                }
            }

            if (processAwarePeerHandles(nextRequest)) {
                if (VDBG) {
                    Log.v(TAG, "RttServiceSynchronized.startRanging: deferring due to PeerHandle "
                            + "Aware requests");
                }
                return;
            }

            nextRequest.cmdId = mNextCommandId++;
            if (mRttNative.rangeRequest(nextRequest.cmdId, nextRequest.request)) {
                mRangingTimeoutMessage.schedule(
                        SystemClock.elapsedRealtime() + HAL_RANGING_TIMEOUT_MS);
            } else {
                Log.w(TAG, "RttServiceSynchronized.startRanging: native rangeRequest call failed");
                try {
                    nextRequest.callback.onRangingFailure(RangingResultCallback.STATUS_CODE_FAIL);
                } catch (RemoteException e) {
                    Log.e(TAG, "RttServiceSynchronized.startRanging: HAL request failed, callback "
                            + "failed -- " + e);
                }
                executeNextRangingRequestIfPossible(true);
            }
            nextRequest.dispatchedToNative = true;
        }

        /**
         * Check request for any PeerHandle Aware requests. If there are any: issue requests to
         * translate the peer ID to a MAC address and abort current execution of the range request.
         * The request will be re-attempted when response is received.
         *
         * In cases of failure: pop the current request and execute the next one. Failures:
         * - Not able to connect to remote service (unlikely)
         * - Request already processed: but we're missing information
         *
         * @return true if need to abort execution, false otherwise.
         */
        private boolean processAwarePeerHandles(RttRequestInfo request) {
            List<Integer> peerIdsNeedingTranslation = new ArrayList<>();
            for (RangingRequest.RttPeer rttPeer: request.request.mRttPeers) {
                if (rttPeer instanceof RangingRequest.RttPeerAware) {
                    RangingRequest.RttPeerAware awarePeer = (RangingRequest.RttPeerAware) rttPeer;
                    if (awarePeer.peerHandle != null && awarePeer.peerMacAddress == null) {
                        peerIdsNeedingTranslation.add(awarePeer.peerHandle.peerId);
                    }
                }
            }

            if (peerIdsNeedingTranslation.size() == 0) {
                return false;
            }

            if (request.peerHandlesTranslated) {
                Log.w(TAG, "processAwarePeerHandles: request=" + request
                        + ": PeerHandles translated - but information still missing!?");
                try {
                    request.callback.onRangingFailure(RangingResultCallback.STATUS_CODE_FAIL);
                } catch (RemoteException e) {
                    Log.e(TAG, "processAwarePeerHandles: onRangingResults failure -- " + e);
                }
                executeNextRangingRequestIfPossible(true);
                return true; // an abort because we removed request and are executing next one
            }

            request.peerHandlesTranslated = true;
            try {
                mAwareBinder.requestMacAddresses(request.uid, peerIdsNeedingTranslation,
                        new IWifiAwareMacAddressProvider.Stub() {
                            @Override
                            public void macAddress(Map peerIdToMacMap) {
                                // ASYNC DOMAIN
                                mHandler.post(() -> {
                                    // BACK TO SYNC DOMAIN
                                    processReceivedAwarePeerMacAddresses(request, peerIdToMacMap);
                                });
                            }
                        });
            } catch (RemoteException e1) {
                Log.e(TAG,
                        "processAwarePeerHandles: exception while calling requestMacAddresses -- "
                                + e1 + ", aborting request=" + request);
                try {
                    request.callback.onRangingFailure(RangingResultCallback.STATUS_CODE_FAIL);
                } catch (RemoteException e2) {
                    Log.e(TAG, "processAwarePeerHandles: onRangingResults failure -- " + e2);
                }
                executeNextRangingRequestIfPossible(true);
                return true; // an abort because we removed request and are executing next one
            }

            return true; // a deferral
        }

        private void processReceivedAwarePeerMacAddresses(RttRequestInfo request,
                Map<Integer, byte[]> peerIdToMacMap) {
            if (VDBG) {
                Log.v(TAG, "processReceivedAwarePeerMacAddresses: request=" + request
                        + ", peerIdToMacMap=" + peerIdToMacMap);
            }

            for (RangingRequest.RttPeer rttPeer: request.request.mRttPeers) {
                if (rttPeer instanceof RangingRequest.RttPeerAware) {
                    RangingRequest.RttPeerAware awarePeer = (RangingRequest.RttPeerAware) rttPeer;
                    if (awarePeer.peerHandle != null && awarePeer.peerMacAddress == null) {
                        awarePeer.peerMacAddress = peerIdToMacMap.get(awarePeer.peerHandle.peerId);
                    }
                }
            }

            // run request again
            startRanging(request);
        }

        private void onRangingResults(int cmdId, List<RttResult> results) {
            if (mRttRequestQueue.size() == 0) {
                Log.e(TAG, "RttServiceSynchronized.onRangingResults: no current RTT request "
                        + "pending!?");
                return;
            }
            mRangingTimeoutMessage.cancel();
            RttRequestInfo topOfQueueRequest = mRttRequestQueue.get(0);

            if (VDBG) {
                Log.v(TAG, "RttServiceSynchronized.onRangingResults: cmdId=" + cmdId
                        + ", topOfQueueRequest=" + topOfQueueRequest + ", results="
                        + Arrays.toString(results.toArray()));
            }

            if (topOfQueueRequest.cmdId != cmdId) {
                Log.e(TAG, "RttServiceSynchronized.onRangingResults: cmdId=" + cmdId
                        + ", does not match pending RTT request cmdId=" + topOfQueueRequest.cmdId);
                return;
            }

            boolean permissionGranted = mWifiPermissionsUtil.checkCallersLocationPermission(
                    topOfQueueRequest.callingPackage, topOfQueueRequest.uid);
            try {
                if (permissionGranted) {
                    List<RangingResult> finalResults = postProcessResults(topOfQueueRequest.request,
                            results);
                    if (VDBG) {
                        Log.v(TAG, "RttServiceSynchronized.onRangingResults: finalResults="
                                + finalResults);
                    }
                    topOfQueueRequest.callback.onRangingResults(finalResults);
                } else {
                    Log.w(TAG, "RttServiceSynchronized.onRangingResults: location permission "
                            + "revoked - not forwarding results");
                    topOfQueueRequest.callback.onRangingFailure(
                            RangingResultCallback.STATUS_CODE_FAIL);
                }
            } catch (RemoteException e) {
                Log.e(TAG,
                        "RttServiceSynchronized.onRangingResults: callback exception -- " + e);
            }

            // clean-up binder death listener: the callback for results is a onetime event - now
            // done with the binder.
            topOfQueueRequest.binder.unlinkToDeath(topOfQueueRequest.dr, 0);

            executeNextRangingRequestIfPossible(true);
        }

        /*
         * Post process the results:
         * - For requests without results: add FAILED results
         * - For Aware requests using PeerHandle: replace MAC address with PeerHandle
         * - Effectively: throws away results which don't match requests
         */
        private List<RangingResult> postProcessResults(RangingRequest request,
                List<RttResult> results) {
            Map<String, RttResult> resultEntries = new HashMap<>();
            for (RttResult result: results) {
                resultEntries.put(new String(HexEncoding.encode(result.addr)), result);
            }

            List<RangingResult> finalResults = new ArrayList<>(request.mRttPeers.size());

            for (RangingRequest.RttPeer peer: request.mRttPeers) {
                byte[] addr;
                if (peer instanceof RangingRequest.RttPeerAp) {
                    addr = NativeUtil.macAddressToByteArray(
                            ((RangingRequest.RttPeerAp) peer).scanResult.BSSID);
                } else if (peer instanceof RangingRequest.RttPeerAware) {
                    addr = ((RangingRequest.RttPeerAware) peer).peerMacAddress;
                } else {
                    Log.w(TAG, "postProcessResults: unknown peer type -- " + peer.getClass());
                    continue;
                }

                RttResult resultForRequest = resultEntries.get(
                        new String(HexEncoding.encode(addr)));
                if (resultForRequest == null) {
                    if (VDBG) {
                        Log.v(TAG, "postProcessResults: missing=" + new String(
                                HexEncoding.encode(addr)));
                    }
                    finalResults.add(
                            new RangingResult(RangingResult.STATUS_FAIL, addr, 0, 0, 0, 0));
                } else {
                    int status = resultForRequest.status == RttStatus.SUCCESS
                            ? RangingResult.STATUS_SUCCESS : RangingResult.STATUS_FAIL;
                    PeerHandle peerHandle = null;
                    if (peer instanceof RangingRequest.RttPeerAware) {
                        peerHandle = ((RangingRequest.RttPeerAware) peer).peerHandle;
                    }

                    if (peerHandle == null) {
                        finalResults.add(
                                new RangingResult(status, addr, resultForRequest.distanceInMm,
                                        resultForRequest.distanceSdInMm, resultForRequest.rssi,
                                        resultForRequest.timeStampInUs));
                    } else {
                        finalResults.add(
                                new RangingResult(status, peerHandle, resultForRequest.distanceInMm,
                                        resultForRequest.distanceSdInMm, resultForRequest.rssi,
                                        resultForRequest.timeStampInUs));
                    }
                }
            }

            return finalResults;
        }

        // dump call (asynchronous most likely)
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            pw.println("  mNextCommandId: " + mNextCommandId);
            pw.println("  mRttRequestQueue: " + mRttRequestQueue);
            mRttNative.dump(fd, pw, args);
        }
    }

    private static class RttRequestInfo {
        public int uid;
        public IBinder binder;
        public IBinder.DeathRecipient dr;
        public String callingPackage;
        public RangingRequest request;
        public IRttCallback callback;

        public int cmdId = 0; // uninitialized cmdId value
        public boolean dispatchedToNative = false;
        public boolean peerHandlesTranslated = false;

        @Override
        public String toString() {
            return new StringBuilder("RttRequestInfo: uid=").append(uid).append(", binder=").append(
                    binder).append(", dr=").append(dr).append(", callingPackage=").append(
                    callingPackage).append(", request=").append(request.toString()).append(
                    ", callback=").append(callback).append(", cmdId=").append(cmdId).append(
                    ", peerHandlesTranslated=").append(peerHandlesTranslated).toString();
        }
    }
}