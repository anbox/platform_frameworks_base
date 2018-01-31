/*
 * Copyright (C) 2016 Simon Fels <morphis@gravedo.de>
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

package com.android.server.wm;

import android.os.ServiceManager;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.content.ClipData;
import android.content.Context;

import com.android.server.wm.DisplayContent;
import com.android.server.wm.Task;
import com.android.server.wm.WindowState;
import com.android.server.wm.WindowList;
import com.android.server.wm.WindowManagerService;

import com.android.server.pm.PackageManagerService;
import android.content.pm.PackageParser;

import java.util.Collection;

public final class AnboxPlatformServiceProxy {
    private static final String TAG = "AnboxPlatformServiceProxy";

    private IBinder mService = null;
    private WindowManagerService mWm = null;
    private WindowList mRemovedWindows =  new WindowList();

    private PackageManagerService mPm = null;

    private static final String DESCRIPTOR = "org.anbox.IPlatformService";

    private static final int TRANSACTION_bootFinished = (IBinder.FIRST_CALL_TRANSACTION);
    private static final int TRANSACTION_updateWindowState = (IBinder.FIRST_CALL_TRANSACTION + 1);
    private static final int TRANSACTION_updatePackageList = (IBinder.FIRST_CALL_TRANSACTION + 2);
    private static final int TRANSACTION_setClipboardData = (IBinder.FIRST_CALL_TRANSACTION + 3);
    private static final int TRANSACTION_getClipboardData = (IBinder.FIRST_CALL_TRANSACTION + 4);

    public AnboxPlatformServiceProxy(WindowManagerService wm, PackageManagerService pm) {
        Log.i(TAG, "Starting up ...");

        mWm = wm;
        mPm = pm;

        mService = ServiceManager.getService("org.anbox.PlatformService");
        if (mService == null)
            Log.w(TAG, "Failed to connect with base service");
    }

    public void notifyBootFinished() {
        if (mService == null)
            return;

        Log.i(TAG, "Sending boot finished signal to host");

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            mService.transact(TRANSACTION_bootFinished, data, reply, 0);
        }
        catch (RemoteException ex) {
            Log.w(TAG, "failed to send boot finished signal to remove binder service: %s" + ex.getMessage());
        }
    }

    public void notifyTaskAdded(int taskId) {
        Log.i(TAG, "Task added id=" + taskId);
    }

    public void notifyTaskRemoved(int taskId) {
        Log.i(TAG, "Task removed id=" + taskId);
    }

    public void removeWindow(WindowState window) {
        mRemovedWindows.add(window);
    }

    private void addWindowStateToParcel(WindowState w, Parcel data) {
        data.writeByte((byte) (w.mHasSurface ? 1 : 0));
        data.writeString(w.mAttrs.packageName);
        data.writeInt(w.mFrame.left);
        data.writeInt(w.mFrame.top);
        data.writeInt(w.mFrame.right);
        data.writeInt(w.mFrame.bottom);
        Task task = w.getTask();
        if (task != null) {
            data.writeInt(task.mTaskId);
            if (task.mStack != null)
                data.writeInt(task.mStack.mStackId);
            else
                data.writeInt(0);
        }
        else {
            data.writeInt(0);
            data.writeInt(0);
        }
        /*
         * Update mRotation value from surface flinger as is,
         * 0 (0 degree), 1 (90 degree), 2 (180 degree), 3 (270 degree)
         * to process at host side
         */
        data.writeInt(mWm.mRotation);
    }

    /*
     * This will be called whenever the WindowSurfacePlayer finished another cycle
     * and submitted all surfaces to SurfaceFlinger for composition. We use this
     * here to submit all necessary information about existing windows to the host
     * so that it can do its compositing correctly.
     */
    public void updateWindowState() {
        if (mWm == null)
            return;

        Parcel data = Parcel.obtain();
        data.writeInterfaceToken("org.anbox.IPlatformService");

        // Send all current windows per display
        final int numDisplays = mWm.mDisplayContents.size();
        data.writeInt(numDisplays);
        for (int n = 0; n < numDisplays; ++n) {
            final DisplayContent display = mWm.mDisplayContents.get(n);
            data.writeInt(display.getDisplayId());
            final WindowList windows = display.getWindowList();
            data.writeInt(windows.size());
            for (int m = 0; m < windows.size(); ++m) {
                final WindowState w = windows.get(m);
                addWindowStateToParcel(w, data);
            }
        }

        // And also send all removed windows so that we can properly track them
        // and their surfaces.
        data.writeInt(mRemovedWindows.size());
        for (int n = 0; n < mRemovedWindows.size(); n++) {
            final WindowState w = mRemovedWindows.get(n);
            addWindowStateToParcel(w, data);
        }

        Parcel reply = Parcel.obtain();
        try {
            mService.transact(TRANSACTION_updateWindowState, data, reply, 0);
        }
        catch (RemoteException ex) {
            Log.w(TAG, "Failed to send updateWindowState request to remote binder service: " + ex.getMessage());
        }

        mRemovedWindows.clear();
    }

    public void sendClipboardData(Context context, ClipData clip) {
        if (clip.getItemCount() == 0)
            return;

        Parcel data = Parcel.obtain();
        data.writeInterfaceToken("org.anbox.IPlatformService");

        ClipData.Item firstItem = clip.getItemAt(0);
        String text = firstItem.coerceToText(context).toString();

        data.writeInt(1);
        data.writeString(text);

        Parcel reply = Parcel.obtain();
        try {
            mService.transact(TRANSACTION_setClipboardData, data, reply, 0);
        }
        catch (RemoteException ex) {
            Log.w(TAG, "Failed to send clipboard data to remote binder service: " + ex.getMessage());
        }
    }

    public ClipData updateClipboardIfNecessary(Context context, ClipData clip) {
        Parcel data = Parcel.obtain();
        data.writeInterfaceToken("org.anbox.IPlatformService");

        Parcel reply = Parcel.obtain();
        try {
            mService.transact(TRANSACTION_getClipboardData, data, reply, 0);
        }
        catch (RemoteException ex) {
            Log.w(TAG, "Failed to retrieve clipboard data from remote binder service: " + ex.getMessage());
            return null;
        }

        if (reply.readInt() == 0)
            return null;

        String text = reply.readString();

        if (clip != null && clip.getItemCount() > 0) {
            ClipData.Item item = clip.getItemAt(0);
            String itemText = item.coerceToText(context).toString();
            if (itemText == text) {
                return null;
            }
        }

        return ClipData.newPlainText("", text);
    }
}
