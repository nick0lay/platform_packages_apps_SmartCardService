/*
 * Copyright (C) 2011, The Android Open Source Project
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
/*
 * Contributed by: Giesecke & Devrient GmbH.
 */

package org.simalliance.openmobileapi.service;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.security.AccessControlException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;


import org.simalliance.openmobileapi.SERecognizerByAID;
import org.simalliance.openmobileapi.service.Channel.SmartcardServiceChannel;
import org.simalliance.openmobileapi.service.Terminal.SmartcardServiceReader;

import org.simalliance.openmobileapi.service.security.AccessControlEnforcer;
import org.simalliance.openmobileapi.service.security.ChannelAccess;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import dalvik.system.DexClassLoader;
import dalvik.system.DexFile;
import dalvik.system.PathClassLoader;



/**
 * The smartcard service is setup with privileges to access smart card hardware.
 * The service enforces the permission
 * 'org.simalliance.openmobileapi.service.permission.BIND'.
 */
public final class SmartcardService extends Service {

    public static final String _TAG = "SmartcardService";
    public static final String _UICC_TERMINAL = "SIM";
    public static final String _eSE_TERMINAL = "eSE";
    public static final String _SD_TERMINAL = "SD";

    /**
     * For now this list is setup in onCreate(), not changed later and therefore
     * not synchronized.
     */
    private Map<String, Terminal> mTerminals
        = new TreeMap<String, Terminal>();

    /* Broadcast receivers */
    private BroadcastReceiver mSimReceiver;
    private BroadcastReceiver mNfcReceiver;
    private BroadcastReceiver mMediaReceiver;

    /* Async task */
    InitialiseTask mInitialiseTask;

    /**
     * ServiceHandler use to load rules from the terminal.
     */
    private ServiceHandler mServiceHandler;

    public SmartcardService() {
        super();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.v(_TAG, Thread.currentThread().getName()
                + " smartcard service onBind");
        if (ISmartcardService.class.getName().equals(intent.getAction())) {
            return mSmartcardBinder;
        }
        return null;
    }

    @Override
    public void onCreate() {
        Log.v(_TAG, Thread.currentThread().getName()
                + " smartcard service onCreate");

        // Start up the thread running the service. Note that we create a
        // separate thread because the service normally runs in the process's
        // main thread, which we don't want to block. We also make it
        // background priority so CPU-intensive work will not disrupt our UI.
        HandlerThread thread = new HandlerThread("SmartCardServiceHandler");
        thread.start();

        // Get the HandlerThread's Looper and use it for our Handler
        mServiceHandler = new ServiceHandler(thread.getLooper());

        createTerminals();
        mInitialiseTask = new InitialiseTask();
        mInitialiseTask.execute();
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println("SMARTCARD SERVICE (dumpsys activity "
                + "service org.simalliance.openmobileapi)");
        writer.println();

        String prefix = "  ";

        if (!Build.IS_DEBUGGABLE) {
            writer.println(prefix + "Your build is not debuggable!");
            writer.println(prefix + "Smartcard service dump is only available"
                    + "for userdebug and eng build");
        } else {
            writer.println(prefix + "List of terminals:");
            for (Terminal terminal : mTerminals.values()) {
                writer.println(prefix + "  " + terminal.getName());
            }
            writer.println();

            for (Terminal terminal : mTerminals.values()) {
                terminal.dump(writer, prefix);
            }
        }
    }

    private class InitialiseTask extends AsyncTask<Void, Void, Void> {

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            
        }

        @Override
        protected Void doInBackground(Void... arg0) {
            
            try {
                initializeAccessControl(null, null);
            } catch (Exception e) {
                // do nothing since this is called where nobody can react.
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            super.onPostExecute(result);
            Log.i(_TAG, "OnPostExecute()");
            registerSimStateChangedEvent(getApplicationContext());
            registerAdapterStateChangedEvent(getApplicationContext());
            registerMediaMountedEvent(getApplicationContext());
            mInitialiseTask = null;
        }
    }

    private void registerSimStateChangedEvent(Context context) {
        Log.v(_TAG, "register SIM_STATE_CHANGED event");

        IntentFilter intentFilter = new IntentFilter(
                "android.intent.action.SIM_STATE_CHANGED");
        mSimReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("android.intent.action.SIM_STATE_CHANGED".equals(intent
                        .getAction())) {
                    final Bundle extras = intent.getExtras();
                    final boolean simReady = (extras != null)
                            && "READY".equals(extras.getString("ss"));
                    final boolean simLoaded = (extras != null)
                            && "LOADED".equals(extras.getString("ss"));
                    if (simReady) {
                        Log.i(_TAG, "SIM is ready. Checking access rules for"
                                + " updates.");
                        mServiceHandler.sendMessage(MSG_LOAD_UICC_RULES, 5);
                    } else if (simLoaded) {
                        Log.i(_TAG, "SIM is loaded. Checking access rules for"
                                + " updates.");
                        mServiceHandler.sendMessage(MSG_LOAD_UICC_RULES, 5);
                    }
                }
            }
        };

        context.registerReceiver(mSimReceiver, intentFilter);
    }

    private void unregisterSimStateChangedEvent(Context context) {
        if (mSimReceiver != null) {
            Log.v(_TAG, "unregister SIM_STATE_CHANGED event");
            context.unregisterReceiver(mSimReceiver);
            mSimReceiver = null;
        }
    }

    private void registerAdapterStateChangedEvent(Context context) {
        Log.v(_TAG, "register ADAPTER_STATE_CHANGED event");

        IntentFilter intentFilter = new IntentFilter(
                "android.nfc.action.ADAPTER_STATE_CHANGED");
        mNfcReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final boolean nfcAdapterAction = intent.getAction().equals(
                        "android.nfc.action.ADAPTER_STATE_CHANGED");
                // Is NFC Adapter turned on?
                final boolean nfcAdapterOn
                    = nfcAdapterAction && intent.getIntExtra(
                                "android.nfc.extra.ADAPTER_STATE", 1) == 3;
                if (nfcAdapterOn) {
                    Log.i(_TAG, "NFC Adapter is ON. Checking access rules for"
                            + " updates.");
                    mServiceHandler.sendMessage(MSG_LOAD_ESE_RULES, 5);
                }
            }
        };
        context.registerReceiver(mNfcReceiver, intentFilter);
    }

    private void unregisterAdapterStateChangedEvent(Context context) {
        if (mNfcReceiver != null) {
            Log.v(_TAG, "unregister ADAPTER_STATE_CHANGED event");
            context.unregisterReceiver(mNfcReceiver);
            mNfcReceiver = null;
        }
    }

    private void registerMediaMountedEvent(Context context) {
        Log.v(_TAG, "register MEDIA_MOUNTED event");

        IntentFilter intentFilter = new IntentFilter(
                "android.intent.action.MEDIA_MOUNTED");
        mMediaReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final boolean mediaMounted = intent.getAction().equals(
                        "android.intent.action.MEDIA_MOUNTED");
                if (mediaMounted) {
                    Log.i(_TAG, "New Media is mounted. Checking access rules"
                            + " for updates.");
                    mServiceHandler.sendMessage(MSG_LOAD_SD_RULES, 5);
                }
            }
        };
        context.registerReceiver(mMediaReceiver, intentFilter);
    }

    private void unregisterMediaMountedEvent(Context context) {
        if (mMediaReceiver != null) {
            Log.v(_TAG, "unregister MEDIA_MOUNTED event");
            context.unregisterReceiver(mMediaReceiver);
            mMediaReceiver = null;
        }
    }

    /**
     * Initalizes Access Control. At least the refresh tag is read and if it
     * differs to the previous one (e.g. is null) the all access rules are read.
     *
     * @param se
     */
    public boolean initializeAccessControl(
            String se,
            ISmartcardServiceCallback callback) {
        return initializeAccessControl(false, se, callback);
    }

    public synchronized boolean initializeAccessControl(
            boolean reset,
            String se,
            ISmartcardServiceCallback callback) {
        boolean result = true;
        Log.i(_TAG, "Initializing Access Control");

        if (callback == null) {
            callback = new ISmartcardServiceCallback.Stub() {
            };
        }

        Collection<Terminal> col = mTerminals.values();
        Iterator<Terminal> iter = col.iterator();
        while (iter.hasNext()) {
            Terminal terminal = iter.next();
            if (terminal == null) {
                
                continue;
            }
            
            if (se == null || terminal.getName().startsWith(se)) {
                boolean isCardPresent = false;
                try {
                    isCardPresent = terminal.isCardPresent();
                } catch (Exception e) {
                    isCardPresent = false;
                    
                }

                if (isCardPresent) {
                    Log.i(_TAG,
                            "Initializing Access Control for "
                                    + terminal.getName());
                    if (reset) {
                        terminal.resetAccessControl();
                    }
                    result &= terminal.initializeAccessControl(true, callback);
                } else {
                    Log.i(_TAG, "NOT initializing Access Control for "
                            + terminal.getName() + " SE not present.");
                }
            }
        }
        return result;
    }

    public void onDestroy() {
        Log.v(_TAG, " smartcard service onDestroy ...");
        for (Terminal terminal : mTerminals.values()) {
            terminal.onSmartcardServiceShutdown();
        }

        // Cancel the inialization background task if still running
        if (mInitialiseTask != null) {
            mInitialiseTask.cancel(true);
        }
        mInitialiseTask = null;

        // Unregister all the broadcast receivers
        unregisterSimStateChangedEvent(getApplicationContext());
        unregisterAdapterStateChangedEvent(getApplicationContext());
        unregisterMediaMountedEvent(getApplicationContext());

        mServiceHandler = null;

        Log.v(_TAG, Thread.currentThread().getName()
                + " ... smartcard service onDestroy");

    }

    private Terminal getTerminal(String reader, SmartcardError error) {
        if (reader == null) {
            Util.setError(error, NullPointerException.class,
                    "reader must not be null");
            return null;
        }
        Terminal terminal = mTerminals.get(reader);
        if (terminal == null) {
            Util.setError(error, IllegalArgumentException.class,
                    "unknown reader");
        }
        return terminal;
    }

    private void createTerminals() {

        // Find Terminal packages
        PackageManager pm = getApplicationContext().getPackageManager();
        List<ResolveInfo> terminallist = pm.queryIntentServices(
                new Intent("org.simalliance.openmobileapi.TERMINAL_DISCOVERY"),
                PackageManager.GET_INTENT_FILTERS);
        Log.e(_TAG, "Numer of terminals: " + terminallist.size());
        for (ResolveInfo info : terminallist) {
            try {
                String packageName = info.serviceInfo.applicationInfo.packageName;
                String sourceDir = getPackageManager().getApplicationInfo(packageName, 0).sourceDir;
                DexClassLoader cl = new DexClassLoader(
                            sourceDir,
                            getCacheDir().getAbsolutePath(),
                            null,
                            ClassLoader.getSystemClassLoader().getSystemClassLoader().getParent());
				String terminalType = (String) cl
                        .loadClass(info.serviceInfo.name)
                        .getMethod("getType", (Class<?>[]) null)
                        .invoke(null, (Object[]) null);
                Log.v(_TAG, "Terminal type: " + terminalType);
                String name = terminalType + getIndexForTerminal(terminalType);
                Log.d(_TAG, "Name: " + name);
                mTerminals.put(name, new Terminal(SmartcardService.this, name, info));
            } catch (Throwable t) {
                Log.e(_TAG, Thread.currentThread().getName()
                        + " CreateReaders Error: "
                        + ((t.getMessage() != null) ? t.getMessage()
                        : "unknown"));
            }
        }
        Log.e(_TAG, "End of Creating BuildinTerminals");
    }

    private String[] createTerminalNamesList() {
        Set<String> names = mTerminals.keySet();
        ArrayList<String> list = new ArrayList<String>(names);

        return list.toArray(new String[list.size()]);
    }

    /**
     * Computes the index that should be assigned to each terminal.
     *
     * @param type of the terminal to compute the index for.
     *
     * @return The index that shall be assigned to the given terminal.
     */
    private int getIndexForTerminal(String type) {
        return getTerminalsOfType(type).length + 1;
    }

    /**
     * Returns an array of terminals of the specified type (SIM/eSE/SD/...).
     *
     * @param terminalType The type of the terminals to be retrieved.
     *
     * @return An array of terminals of the specified type.
     */
    private Terminal[] getTerminalsOfType(String terminalType) {
        ArrayList<Terminal> terminals = new ArrayList<Terminal>();
        int index = 1;
        String name = terminalType + index;
        while (mTerminals.containsKey(name)) {
            terminals.add(mTerminals.get(name));
            index++;
            name = terminalType + index;
        }

        return terminals.toArray(new Terminal[terminals.size()]);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private Object[] getBuildinTerminalClasses() {
        ArrayList classes = new ArrayList();
        try {
            String packageName = "org.simalliance.openmobileapi.service";
            String apkName = getPackageManager().getApplicationInfo(
                    packageName, 0).sourceDir;
            DexClassLoader dexClassLoader = new DexClassLoader(apkName,
                    getApplicationContext().getFilesDir().getAbsolutePath(),
                    null, getClass().getClassLoader());

            Class terminalClass = Class.forName(
                    "org.simalliance.openmobileapi.service.Terminal", true,
                    dexClassLoader);
            if (terminalClass == null) {
                return classes.toArray();
            }

            DexFile dexFile = new DexFile(apkName);
            Enumeration<String> classFileNames = dexFile.entries();
            while (classFileNames.hasMoreElements()) {
                String className = classFileNames.nextElement();
                Class clazz = Class.forName(className);
                Class superClass = clazz.getSuperclass();
                if (superClass != null
                        && superClass.equals(terminalClass)
                        && !className.equals("org.simalliance.openmobileapi.service.AddonTerminal")) {
                    classes.add(clazz);
                }
            }
        } catch (Throwable exp) {
            // nothing to to
        }
        return classes.toArray();
    }

    /**
     * The smartcard service interface implementation.
     */
    private final ISmartcardService.Stub mSmartcardBinder
        = new ISmartcardService.Stub() {

        @Override
        public String[] getReaders(SmartcardError error)
                throws RemoteException {
            Util.clearError(error);
            Log.v(_TAG, "getReaders()");
            return createTerminalNamesList();
        }

        @Override
        public ISmartcardServiceReader getReader(String reader,
                SmartcardError error) throws RemoteException {
            Util.clearError(error);
            Terminal terminal = (Terminal) getTerminal(reader, error);
            if (terminal != null) {
                return terminal.new SmartcardServiceReader(
                        SmartcardService.this);
            }
            Util.setError(error, IllegalArgumentException.class,
                    "invalid reader name");
            return null;
        }

        @Override
        public synchronized boolean[] isNFCEventAllowed(String reader,
                byte[] aid, String[] packageNames,
                ISmartcardServiceCallback callback, SmartcardError error)
                throws RemoteException {
            Util.clearError(error);
            try {
                if (callback == null) {
                    Util.setError(error, NullPointerException.class,
                            "callback must not be null");
                    return null;
                }
                Terminal terminal = getTerminal(reader, error);
                if (terminal == null) {
                    return null;
                }
                if (aid == null || aid.length == 0) {
                    aid = new byte[] { 0x00, 0x00, 0x00, 0x00, 0x00 };
                }
                if (aid.length < 5 || aid.length > 16) {
                    Util.setError(error, IllegalArgumentException.class,
                            "AID out of range");
                    return null;
                }
                if (packageNames == null || packageNames.length == 0) {
                    Util.setError(error, IllegalArgumentException.class,
                            "process names not specified");
                    return null;
                }
                AccessControlEnforcer ac = null;
                if (terminal.getAccessControlEnforcer() == null) {
                    ac = new AccessControlEnforcer(terminal);
                } else {
                    ac = terminal.getAccessControlEnforcer();
                }
                ac.setPackageManager(getPackageManager());
                ac.initialize(true, callback);
                return ac.isNFCEventAllowed(aid, packageNames, callback);
            } catch (Exception e) {
                Util.setError(error, e);
                Log.v(_TAG, "isNFCEventAllowed Exception: " + e.getMessage());
                return null;
            }
        }
    };

    /**
     * The smartcard service interface implementation.
     */
    final class SmartcardServiceSession extends ISmartcardServiceSession.Stub {

        private final SmartcardServiceReader mReader;
        /** List of open channels in use of by this client. */
        private final Set<Channel> mChannels = new HashSet<Channel>();

        private final Object mLock = new Object();

        private boolean mIsClosed;

        private byte[] mAtr;

        public SmartcardServiceSession(SmartcardServiceReader reader) {
            mReader = reader;
            mAtr = mReader.getAtr();
            mIsClosed = false;
        }

        @Override
        public ISmartcardServiceReader getReader() throws RemoteException {
            return mReader;
        }

        @Override
        public byte[] getAtr() throws RemoteException {
            return mAtr;
        }

        @Override
        public void close(SmartcardError error) throws RemoteException {
            Util.clearError(error);
            if (mReader == null) {
                return;
            }
            try {
                mReader.closeSession(this);
            } catch (CardException e) {
                Util.setError(error, e);
            }
        }

        @Override
        public void closeChannels(SmartcardError error) throws RemoteException {
            synchronized (mLock) {
                
                Iterator<Channel> iter = mChannels.iterator();
                try {
                    while (iter.hasNext()) {
                        Channel channel = iter.next();
                        if (channel != null && !channel.isClosed()) {
                            try {
                                channel.close();
                                // close changes indirectly mChannels, so we
                                // need a new iterator.
                                iter = mChannels.iterator();
                            } catch (Exception ignore) {
                                Log.e(_TAG, "ServiceSession channel - close"
                                        + " Exception " + ignore.getMessage());
                            }
                            channel.setClosed();
                        }
                    }
                    mChannels.clear();
                } catch (Exception e) {
                    Log.e(_TAG,
                            "ServiceSession closeChannels Exception "
                                    + e.getMessage());
                }
            }
        }

        @Override
        public boolean isClosed() throws RemoteException {
            
            return mIsClosed;
        }

        @Override
        public ISmartcardServiceChannel openBasicChannel(
                ISmartcardServiceCallback callback, SmartcardError error)
                throws RemoteException {
            return openBasicChannelAid(null, callback, error);
        }

        @Override
        public ISmartcardServiceChannel openBasicChannelAid(byte[] aid,
                ISmartcardServiceCallback callback, SmartcardError error)
                throws RemoteException {
            Util.clearError(error);
            if (isClosed()) {
                Util.setError(error, IllegalStateException.class,
                        "session is closed");
                return null;
            }
            if (callback == null) {
                Util.setError(error, IllegalStateException.class,
                        "callback must not be null");
                return null;
            }
            if (mReader == null) {
                Util.setError(error, IllegalStateException.class,
                        "reader must not be null");
                return null;
            }

            try {
                boolean noAid = false;
                if (aid == null || aid.length == 0) {
                    aid = new byte[] { 0x00, 0x00, 0x00, 0x00, 0x00 };
                    noAid = true;
                }

                if (aid.length < 5 || aid.length > 16) {
                    Util.setError(error, IllegalArgumentException.class,
                            "AID out of range");
                    return null;
                }

                
                String packageName = Util.getPackageNameFromCallingUid(
                                                                getApplicationContext(),
                                                                Binder.getCallingUid());
                Log.v(_TAG, "Enable access control on basic channel for "
                        + packageName);
                ChannelAccess channelAccess = mReader.getTerminal()
                        .setUpChannelAccess(getPackageManager(), aid,
                                packageName, callback);
                Log.v(_TAG, "Access control successfully enabled.");

                channelAccess.setCallingPid(Binder.getCallingPid());
                
                

                Log.v(_TAG, "OpenBasicChannel(AID)");
                Channel channel = null;
                if (noAid) {
                    channel = mReader.getTerminal().openBasicChannel(this,
                            callback);
                } else {
                    channel = mReader.getTerminal().openBasicChannel(this, aid,
                            callback);
                }
                
                channel.setChannelAccess(channelAccess);
                
                Log.v(_TAG,
                        "Open basic channel success. Channel: "
                                + channel.getChannelNumber());

                SmartcardServiceChannel basicChannel
                    = channel.new SmartcardServiceChannel(this);
                mChannels.add(channel);
                return basicChannel;

            } catch (Exception e) {
                Util.setError(error, e);
                Log.v(_TAG, "OpenBasicChannel Exception: " + e.getMessage());
                return null;
            }
        }

        @Override
        public ISmartcardServiceChannel openLogicalChannel(byte[] aid,
                ISmartcardServiceCallback callback, SmartcardError error)
                throws RemoteException {
            Util.clearError(error);

            if (isClosed()) {
                Util.setError(error, IllegalStateException.class,
                        "session is closed");
                return null;
            }

            if (callback == null) {
                Util.setError(error, IllegalStateException.class,
                        "callback must not be null");
                return null;
            }
            if (mReader == null) {
                Util.setError(error, IllegalStateException.class,
                        "reader must not be null");
                return null;
            }

            try {
                boolean noAid = false;
                if (aid == null || aid.length == 0) {
                    aid = new byte[] { 0x00, 0x00, 0x00, 0x00, 0x00 };
                    noAid = true;
                }

                if (aid.length < 5 || aid.length > 16) {
                    Util.setError(error, IllegalArgumentException.class,
                            "AID out of range");
                    return null;
                }

                
                String packageName = Util.getPackageNameFromCallingUid(
                                                                getApplicationContext(),
                                                                Binder.getCallingUid());
                Log.v(_TAG, "Enable access control on logical channel for "
                        + packageName);
                ChannelAccess channelAccess = mReader.getTerminal()
                        .setUpChannelAccess(getPackageManager(), aid,
                                packageName, callback);
                Log.v(_TAG, "Access control successfully enabled.");
                channelAccess.setCallingPid(Binder.getCallingPid());
                
                
                Log.v(_TAG, "OpenLogicalChannel");
                Channel channel = null;
                if (noAid) {
                    channel = mReader.getTerminal().openLogicalChannel(this,
                            callback);
                } else {
                    channel = mReader.getTerminal().openLogicalChannel(this,
                            aid, callback);
                }
                
                channel.setChannelAccess(channelAccess);
                
                Log.v(_TAG, "Open logical channel successfull. Channel: "
                        + channel.getChannelNumber());
                SmartcardServiceChannel logicalChannel
                    = channel.new SmartcardServiceChannel(this);
                mChannels.add(channel);
                return logicalChannel;
            } catch (Exception e) {
                Util.setError(error, e);
                Log.v(_TAG, "OpenLogicalChannel Exception: " + e.getMessage());
                return null;
            }
        }

        void setClosed() {
            mIsClosed = true;
            
        }

        /**
         * Closes the specified channel. <br>
         * After calling this method the session can not be used for the
         * communication with the secure element any more.
         *
         * @param channel the channel handle obtained by an open channel
         *        command.
         */
        void removeChannel(Channel channel) {
            if (channel == null) {
                return;
            }
            mChannels.remove(channel);
        }
    }

    /*
     * Handler Thread used to load and initiate ChannelAccess condition
     */
    public static final int MSG_LOAD_UICC_RULES = 1;
    public static final int MSG_LOAD_ESE_RULES = 2;
    public static final int MSG_LOAD_SD_RULES = 3;

    public static final int NUMBER_OF_TRIALS = 3;
    public static final long WAIT_TIME = 1000;

    private final class ServiceHandler extends Handler {

        @SuppressLint("HandlerLeak")
        public ServiceHandler(Looper looper) {
            super(looper);
        }

        public void sendMessage(int what, int nbTries) {
            mServiceHandler.removeMessages(what);
            Message newMsg = mServiceHandler.obtainMessage(what, nbTries, 0);
            mServiceHandler.sendMessage(newMsg);
        }

        @Override
        public void handleMessage(Message msg) {
            boolean result = true;

            Log.i(_TAG, "Handle msg: what=" + msg.what + " nbTries="
                    + msg.arg1);

            switch (msg.what) {
            case MSG_LOAD_UICC_RULES:
                try {
                    result = initializeAccessControl(
                            true, _UICC_TERMINAL, null);
                } catch (Exception e) {
                    Log.e(_TAG, "Got exception:" + e);
                }
                break;

            case MSG_LOAD_ESE_RULES:
                try {
                    result = initializeAccessControl(true, _eSE_TERMINAL, null);
                } catch (Exception e) {
                    Log.e(_TAG, "Got exception:" + e);
                }
                break;

            case MSG_LOAD_SD_RULES:
                try {
                    result = initializeAccessControl(true, _SD_TERMINAL, null);
                } catch (Exception e) {
                    Log.e(_TAG, "Got exception:" + e);
                }
                break;
            }

            if (!result && msg.arg1 > 0) {
                // Try to re-post the message
                Log.e(_TAG, "Fail to load rules: Let's try another time ("
                        + msg.arg1 + " remaining attempt");
                Message newMsg = mServiceHandler.obtainMessage(msg.what,
                        msg.arg1 - 1, 0);
                mServiceHandler.sendMessageDelayed(newMsg, WAIT_TIME);
            }
        }
    }
}
