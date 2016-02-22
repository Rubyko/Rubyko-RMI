/*
 * Copyright (c) 1997, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package rubyko.sun.rmi.server;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.lang.Process;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.channels.Channel;
import java.nio.channels.ServerSocketChannel;
import rubyko.java.rmi.AccessException;
import rubyko.java.rmi.ConnectException;
import rubyko.java.rmi.ConnectIOException;
import rubyko.java.rmi.MarshalledObject;
import rubyko.java.rmi.NoSuchObjectException;
import rubyko.java.rmi.Remote;
import rubyko.java.rmi.RemoteException;
import rubyko.java.rmi.activation.ActivationDesc;
import rubyko.java.rmi.activation.ActivationException;
import rubyko.java.rmi.activation.ActivationGroupDesc;
import rubyko.java.rmi.activation.ActivationGroup;
import rubyko.java.rmi.activation.ActivationGroupID;
import rubyko.java.rmi.activation.ActivationID;
import rubyko.java.rmi.activation.ActivationInstantiator;
import rubyko.java.rmi.activation.ActivationMonitor;
import rubyko.java.rmi.activation.ActivationSystem;
import rubyko.java.rmi.activation.Activator;
import rubyko.java.rmi.activation.UnknownGroupException;
import rubyko.java.rmi.activation.UnknownObjectException;
import rubyko.java.rmi.server.ObjID;
import rubyko.java.rmi.server.RMIClassLoader;
import rubyko.java.rmi.server.RMIServerSocketFactory;
import rubyko.java.rmi.server.RemoteServer;
import rubyko.java.rmi.server.UnicastRemoteObject;
import java.security.AccessControlException;
import java.security.AccessController;
import java.security.AllPermission;
import java.security.CodeSource;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.Policy;
import java.security.PrivilegedAction;
import java.security.PrivilegedExceptionAction;
import java.security.cert.Certificate;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import rubyko.sun.rmi.registry.RegistryImpl;
import rubyko.sun.rmi.runtime.NewThreadAction;
import rubyko.sun.rmi.server.UnicastServerRef;
import rubyko.sun.rmi.transport.LiveRef;
import rubyko.sun.security.action.GetBooleanAction;
import rubyko.sun.security.action.GetIntegerAction;
import rubyko.sun.security.action.GetPropertyAction;
import rubyko.com.sun.rmi.rmid.ExecPermission;
import rubyko.com.sun.rmi.rmid.ExecOptionPermission;

/**
 * The Activator facilitates remote object activation. A "faulting"
 * remote reference calls the activator's <code>activate</code> method
 * to obtain a "live" reference to a activatable remote object. Upon
 * receiving a request for activation, the activator looks up the
 * activation descriptor for the activation identifier, id, determines
 * the group in which the object should be activated and invokes the
 * activate method on the object's activation group (described by the
 * remote interface <code>ActivationInstantiator</code>). The
 * activator initiates the execution of activation groups as
 * necessary. For example, if an activation group for a specific group
 * identifier is not already executing, the activator will spawn a
 * child process for the activation group. <p>
 *
 * The activator is responsible for monitoring and detecting when
 * activation groups fail so that it can remove stale remote references
 * from its internal tables. <p>
 *
 * @author      Ann Wollrath
 * @since       1.2
 */
public class Activation implements Serializable {

    /** indicate compatibility with JDK 1.2 version of class */
    private static final long serialVersionUID = 2921265612698155191L;

    /** exec policy object */
    private static Object execPolicy;
    private static Method execPolicyMethod;
    private static boolean debugExec;

    /** maps activation id to its respective group id */
    private Map<ActivationID,ActivationGroupID> idTable =
        new ConcurrentHashMap<>();
    /** maps group id to its GroupEntry groups */
    private Map<ActivationGroupID,GroupEntry> groupTable =
        new ConcurrentHashMap<>();


    /** number of simultaneous group exec's */
    private transient int groupSemaphore;
    /** counter for numbering groups */
    private transient int groupCounter;

    /** the java command */
    // accessed by GroupEntry
    private transient String[] command;
    /** timeout on wait for child process to be created or destroyed */
    private static final long groupTimeout =
        getInt("sun.rmi.activation.groupTimeout", 60000);
    /** timeout on wait for child process to be created */
    private static final long execTimeout =
        getInt("sun.rmi.activation.execTimeout", 30000);

    private static final Object initLock = new Object();
    private static boolean initDone = false;

    // this should be a *private* method since it is privileged
    private static int getInt(String name, int def) {
        return AccessController.doPrivileged(new GetIntegerAction(name, def));
    }

    private transient Activator activator;
    private transient Activator activatorStub;
    private transient ActivationSystem system;
    private transient ActivationSystem systemStub;
    private transient ActivationMonitor monitor;
    private transient volatile boolean shuttingDown = false;
    private transient volatile Object startupLock;
    private transient Thread shutdownHook;

    private static ResourceBundle resources = null;

    /**
     * Create an uninitialized instance of Activation that can be
     * populated with log data.  This is only called when the initial
     * snapshot is taken during the first incarnation of rmid.
     */
    private Activation() {}

   
    /**
     * Previous versions used HashMap instead of ConcurrentHashMap.
     * Replace any HashMaps found during deserialization with
     * ConcurrentHashMaps.
     */
    private void readObject(ObjectInputStream ois)
        throws IOException, ClassNotFoundException
    {
        ois.defaultReadObject();
        if (! (groupTable instanceof ConcurrentHashMap)) {
            groupTable = new ConcurrentHashMap<>(groupTable);
        }
        if (! (idTable instanceof ConcurrentHashMap)) {
            idTable = new ConcurrentHashMap<>(idTable);
        }
    }

    class ActivatorImpl extends RemoteServer implements Activator {
        // Because ActivatorImpl has a fixed ObjID, it can be
        // called by clients holding stale remote references.  Each of
        // its remote methods, then, must check startupLock (calling
        // checkShutdown() is easiest).

        private static final long serialVersionUID = -3654244726254566136L;

        /**
         * Construct a new Activator on a specified port.
         */
        ActivatorImpl(int port, RMIServerSocketFactory ssf)
            throws RemoteException
        {
            /* Server ref must be created and assigned before remote object
             * 'this' can be exported.
             */
            LiveRef lref =
                new LiveRef(new ObjID(ObjID.ACTIVATOR_ID), port, null, ssf);
            UnicastServerRef uref = new UnicastServerRef(lref);
            ref = uref;
            uref.exportObject(this, null, false);
        }

        public MarshalledObject<? extends Remote> activate(ActivationID id,
                                                           boolean force)
            throws ActivationException, UnknownObjectException, RemoteException
        {
            checkShutdown();
            return getGroupEntry(id).activate(id, force);
        }
    }

    class ActivationMonitorImpl extends UnicastRemoteObject
        implements ActivationMonitor
    {
        private static final long serialVersionUID = -6214940464757948867L;

        ActivationMonitorImpl(int port, RMIServerSocketFactory ssf)
            throws RemoteException
        {
            super(port, null, ssf);
        }

        public void inactiveObject(ActivationID id)
            throws UnknownObjectException, RemoteException
        {
            try {
                checkShutdown();
            } catch (ActivationException e) {
                return;
            }
            RegistryImpl.checkAccess("Activator.inactiveObject");
            getGroupEntry(id).inactiveObject(id);
        }

        public void activeObject(ActivationID id,
                                 MarshalledObject<? extends Remote> mobj)
            throws UnknownObjectException, RemoteException
        {
            try {
                checkShutdown();
            } catch (ActivationException e) {
                return;
            }
            RegistryImpl.checkAccess("ActivationSystem.activeObject");
            getGroupEntry(id).activeObject(id, mobj);
        }

        public void inactiveGroup(ActivationGroupID id,
                                  long incarnation)
            throws UnknownGroupException, RemoteException
        {
            try {
                checkShutdown();
            } catch (ActivationException e) {
                return;
            }
            RegistryImpl.checkAccess("ActivationMonitor.inactiveGroup");
            getGroupEntry(id).inactiveGroup(incarnation, false);
        }

	
    }


    class ActivationSystemImpl
        extends RemoteServer
        implements ActivationSystem
    {
        private static final long serialVersionUID = 9100152600327688967L;

        // Because ActivationSystemImpl has a fixed ObjID, it can be
        // called by clients holding stale remote references.  Each of
        // its remote methods, then, must check startupLock (calling
        // checkShutdown() is easiest).
        ActivationSystemImpl(int port, RMIServerSocketFactory ssf)
            throws RemoteException
        {
            /* Server ref must be created and assigned before remote object
             * 'this' can be exported.
             */
            LiveRef lref = new LiveRef(new ObjID(4), port, null, ssf);
            UnicastServerRef uref = new UnicastServerRef(lref);
            ref = uref;
            uref.exportObject(this, null, false);
        }

        public ActivationID registerObject(ActivationDesc desc)
            throws ActivationException, UnknownGroupException, RemoteException
        {
            checkShutdown();
            RegistryImpl.checkAccess("ActivationSystem.registerObject");

            ActivationGroupID groupID = desc.getGroupID();
            ActivationID id = new ActivationID(activatorStub);
            getGroupEntry(groupID).registerObject(id, desc, true);
            return id;
        }

        public void unregisterObject(ActivationID id)
            throws ActivationException, UnknownObjectException, RemoteException
        {
            checkShutdown();
            RegistryImpl.checkAccess("ActivationSystem.unregisterObject");
            getGroupEntry(id).unregisterObject(id, true);
        }

        public ActivationGroupID registerGroup(ActivationGroupDesc desc)
            throws ActivationException, RemoteException
        {
            checkShutdown();
            RegistryImpl.checkAccess("ActivationSystem.registerGroup");
            checkArgs(desc, null);

            ActivationGroupID id = new ActivationGroupID(systemStub);
            GroupEntry entry = new GroupEntry(id, desc);
            // table insertion must take place before log update
            groupTable.put(id, entry);
            return id;
        }

        public ActivationMonitor activeGroup(ActivationGroupID id,
                                             ActivationInstantiator group,
                                             long incarnation)
            throws ActivationException, UnknownGroupException, RemoteException
        {
            checkShutdown();
            RegistryImpl.checkAccess("ActivationSystem.activeGroup");

            getGroupEntry(id).activeGroup(group, incarnation);
            return monitor;
        }

        public void unregisterGroup(ActivationGroupID id)
            throws ActivationException, UnknownGroupException, RemoteException
        {
            checkShutdown();
            RegistryImpl.checkAccess("ActivationSystem.unregisterGroup");

            // remove entry before unregister so state is updated before
            // logged
            removeGroupEntry(id).unregisterGroup(true);
        }

        public ActivationDesc setActivationDesc(ActivationID id,
                                                ActivationDesc desc)
            throws ActivationException, UnknownObjectException, RemoteException
        {
            checkShutdown();
            RegistryImpl.checkAccess("ActivationSystem.setActivationDesc");

            if (!getGroupID(id).equals(desc.getGroupID())) {
                throw new ActivationException(
                    "ActivationDesc contains wrong group");
            }
            return getGroupEntry(id).setActivationDesc(id, desc, true);
        }

        public ActivationGroupDesc setActivationGroupDesc(ActivationGroupID id,
                                                          ActivationGroupDesc desc)
            throws ActivationException, UnknownGroupException, RemoteException
        {
            checkShutdown();
            RegistryImpl.checkAccess(
                "ActivationSystem.setActivationGroupDesc");

            checkArgs(desc, null);
            return getGroupEntry(id).setActivationGroupDesc(id, desc, true);
        }

        public ActivationDesc getActivationDesc(ActivationID id)
            throws ActivationException, UnknownObjectException, RemoteException
        {
            checkShutdown();
            RegistryImpl.checkAccess("ActivationSystem.getActivationDesc");

            return getGroupEntry(id).getActivationDesc(id);
        }

        public ActivationGroupDesc getActivationGroupDesc(ActivationGroupID id)
            throws ActivationException, UnknownGroupException, RemoteException
        {
            checkShutdown();
            RegistryImpl.checkAccess
                ("ActivationSystem.getActivationGroupDesc");

            return getGroupEntry(id).desc;
        }

        /**
         * Shutdown the activation system. Destroys all groups spawned by
         * the activation daemon and exits the activation daemon.
         */
        public void shutdown() throws AccessException {
            RegistryImpl.checkAccess("ActivationSystem.shutdown");

            Object lock = startupLock;
            if (lock != null) {
                synchronized (lock) {
                    // nothing
                }
            }

            synchronized (Activation.this) {
                if (!shuttingDown) {
                    shuttingDown = true;
                    (new Shutdown()).start();
                }
            }
        }
    }

    private void checkShutdown() throws ActivationException {
        // if the startup critical section is running, wait until it
        // completes/fails before continuing with the remote call.
        Object lock = startupLock;
        if (lock != null) {
            synchronized (lock) {
                // nothing
            }
        }

        if (shuttingDown == true) {
            throw new ActivationException(
                "activation system shutting down");
        }
    }

    private static void unexport(Remote obj) {
        for (;;) {
            try {
                if (UnicastRemoteObject.unexportObject(obj, false) == true) {
                    break;
                } else {
                    Thread.sleep(100);
                }
            } catch (Exception e) {
                continue;
            }
        }
    }

    /**
     * Thread to shutdown rmid.
     */
    private class Shutdown extends Thread {
        Shutdown() {
            super("rmid Shutdown");
        }

        public void run() {
            try {
                /*
                 * Unexport activation system services
                 */
                unexport(activator);
                unexport(system);

                // destroy all child processes (groups)
                for (GroupEntry groupEntry : groupTable.values()) {
                    groupEntry.shutdown();
                }

                Runtime.getRuntime().removeShutdownHook(shutdownHook);

                /*
                 * Unexport monitor safely since all processes are destroyed.
                 */
                unexport(monitor);



            } finally {
                /*
                 * Now exit... A System.exit should only be done if
                 * the RMI activation system daemon was started up
                 * by the main method below (in which should always
                 * be the case since the Activation constructor is private).
                 */
                System.err.println(getTextResource("rmid.daemon.shutdown"));
                System.exit(0);
            }
        }
    }

    /**
     * Returns the groupID for a given id of an object in the group.
     * Throws UnknownObjectException if the object is not registered.
     */
    private ActivationGroupID getGroupID(ActivationID id)
        throws UnknownObjectException
    {
        ActivationGroupID groupID = idTable.get(id);
        if (groupID != null) {
            return groupID;
        }
        throw new UnknownObjectException("unknown object: " + id);
    }

    /**
     * Returns the group entry for the group id, optionally removing it.
     * Throws UnknownGroupException if the group is not registered.
     */
    private GroupEntry getGroupEntry(ActivationGroupID id, boolean rm)
        throws UnknownGroupException
    {
        if (id.getClass() == ActivationGroupID.class) {
            GroupEntry entry;
            if (rm) {
                entry = groupTable.remove(id);
            } else {
                entry = groupTable.get(id);
            }
            if (entry != null && !entry.removed) {
                return entry;
            }
        }
        throw new UnknownGroupException("group unknown");
    }

    /**
     * Returns the group entry for the group id. Throws
     * UnknownGroupException if the group is not registered.
     */
    private GroupEntry getGroupEntry(ActivationGroupID id)
        throws UnknownGroupException
    {
        return getGroupEntry(id, false);
    }

    /**
     * Removes and returns the group entry for the group id. Throws
     * UnknownGroupException if the group is not registered.
     */
    private GroupEntry removeGroupEntry(ActivationGroupID id)
        throws UnknownGroupException
    {
        return getGroupEntry(id, true);
    }

    /**
     * Returns the group entry for the object's id. Throws
     * UnknownObjectException if the object is not registered or the
     * object's group is not registered.
     */
    private GroupEntry getGroupEntry(ActivationID id)
        throws UnknownObjectException
    {
        ActivationGroupID gid = getGroupID(id);
        GroupEntry entry = groupTable.get(gid);
        if (entry != null && !entry.removed) {
            return entry;
        }
        throw new UnknownObjectException("object's group removed");
    }

    /**
     * Container for group information: group's descriptor, group's
     * instantiator, flag to indicate pending group creation, and
     * table of the objects that are activated in the group.
     *
     * WARNING: GroupEntry objects should not be written into log file
     * updates.  GroupEntrys are inner classes of Activation and they
     * can not be serialized independent of this class.  If the
     * complete Activation system is written out as a log update, the
     * point of having updates is nullified.
     */
    private class GroupEntry implements Serializable {

        /** indicate compatibility with JDK 1.2 version of class */
        private static final long serialVersionUID = 7222464070032993304L;
        private static final int MAX_TRIES = 2;
        private static final int NORMAL = 0;
        private static final int CREATING = 1;
        private static final int TERMINATE = 2;
        private static final int TERMINATING = 3;

        ActivationGroupDesc desc = null;
        ActivationGroupID groupID = null;
        long incarnation = 0;
        Map<ActivationID,ObjectEntry> objects = new HashMap<>();
        Set<ActivationID> restartSet = new HashSet<>();

        transient ActivationInstantiator group = null;
        transient int status = NORMAL;
        transient long waitTime = 0;
        transient String groupName = null;
        transient Process child = null;
        transient boolean removed = false;
        transient Watchdog watchdog = null;

        GroupEntry(ActivationGroupID groupID, ActivationGroupDesc desc) {
            this.groupID = groupID;
            this.desc = desc;
        }

        void restartServices() {
            Iterator<ActivationID> iter = null;

            synchronized (this) {
                if (restartSet.isEmpty()) {
                    return;
                }

                /*
                 * Clone the restartSet so the set does not have to be locked
                 * during iteration. Locking the restartSet could cause
                 * deadlock if an object we are restarting caused another
                 * object in this group to be activated.
                 */
                iter = (new HashSet<ActivationID>(restartSet)).iterator();
            }

            while (iter.hasNext()) {
                ActivationID id = iter.next();
                try {
                    activate(id, true);
                } catch (Exception e) {
                    if (shuttingDown) {
                        return;
                    }
                    System.err.println(
                        getTextResource("rmid.restart.service.warning"));
                    e.printStackTrace();
                }
            }
        }

        synchronized void activeGroup(ActivationInstantiator inst,
                                      long instIncarnation)
            throws ActivationException, UnknownGroupException
        {
            if (incarnation != instIncarnation) {
                throw new ActivationException("invalid incarnation");
            }

            if (group != null) {
                if (group.equals(inst)) {
                    return;
                } else {
                    throw new ActivationException("group already active");
                }
            }

            if (child != null && status != CREATING) {
                throw new ActivationException("group not being created");
            }

            group = inst;
            status = NORMAL;
            notifyAll();
        }

        private void checkRemoved() throws UnknownGroupException {
            if (removed) {
                throw new UnknownGroupException("group removed");
            }
        }

        private ObjectEntry getObjectEntry(ActivationID id)
            throws UnknownObjectException
        {
            if (removed) {
                throw new UnknownObjectException("object's group removed");
            }
            ObjectEntry objEntry = objects.get(id);
            if (objEntry == null) {
                throw new UnknownObjectException("object unknown");
            }
            return objEntry;
        }

        synchronized void registerObject(ActivationID id,
                                         ActivationDesc desc,
                                         boolean addRecord)
            throws UnknownGroupException, ActivationException
        {
            checkRemoved();
            objects.put(id, new ObjectEntry(desc));
            if (desc.getRestartMode() == true) {
                restartSet.add(id);
            }

            // table insertion must take place before log update
            idTable.put(id, groupID);

        }

        synchronized void unregisterObject(ActivationID id, boolean addRecord)
            throws UnknownGroupException, ActivationException
        {
            ObjectEntry objEntry = getObjectEntry(id);
            objEntry.removed = true;
            objects.remove(id);
            if (objEntry.desc.getRestartMode() == true) {
                restartSet.remove(id);
            }

            // table removal must take place before log update
            idTable.remove(id);
        }

        synchronized void unregisterGroup(boolean addRecord)
           throws UnknownGroupException, ActivationException
        {
            checkRemoved();
            removed = true;
            for (Map.Entry<ActivationID,ObjectEntry> entry :
                     objects.entrySet())
            {
                ActivationID id = entry.getKey();
                idTable.remove(id);
                ObjectEntry objEntry = entry.getValue();
                objEntry.removed = true;
            }
            objects.clear();
            restartSet.clear();
            reset();
            childGone();

        }

        synchronized ActivationDesc setActivationDesc(ActivationID id,
                                                      ActivationDesc desc,
                                                      boolean addRecord)
            throws UnknownObjectException, UnknownGroupException,
                   ActivationException
        {
            ObjectEntry objEntry = getObjectEntry(id);
            ActivationDesc oldDesc = objEntry.desc;
            objEntry.desc = desc;
            if (desc.getRestartMode() == true) {
                restartSet.add(id);
            } else {
                restartSet.remove(id);
            }

            return oldDesc;
        }

        synchronized ActivationDesc getActivationDesc(ActivationID id)
            throws UnknownObjectException, UnknownGroupException
        {
            return getObjectEntry(id).desc;
        }

        synchronized ActivationGroupDesc setActivationGroupDesc(
                ActivationGroupID id,
                ActivationGroupDesc desc,
                boolean addRecord)
            throws UnknownGroupException, ActivationException
        {
            checkRemoved();
            ActivationGroupDesc oldDesc = this.desc;
            this.desc = desc;
            // state update should occur before log update
            return oldDesc;
        }

        synchronized void inactiveGroup(long incarnation, boolean failure)
            throws UnknownGroupException
        {
            checkRemoved();
            if (this.incarnation != incarnation) {
                throw new UnknownGroupException("invalid incarnation");
            }

            reset();
            if (failure) {
                terminate();
            } else if (child != null && status == NORMAL) {
                status = TERMINATE;
                watchdog.noRestart();
            }
        }

        synchronized void activeObject(ActivationID id,
                                       MarshalledObject<? extends Remote> mobj)
                throws UnknownObjectException
        {
            getObjectEntry(id).stub = mobj;
        }

        synchronized void inactiveObject(ActivationID id)
            throws UnknownObjectException
        {
            getObjectEntry(id).reset();
        }

        private synchronized void reset() {
            group = null;
            for (ObjectEntry objectEntry : objects.values()) {
                objectEntry.reset();
            }
        }

        private void childGone() {
            if (child != null) {
                child = null;
                watchdog.dispose();
                watchdog = null;
                status = NORMAL;
                notifyAll();
            }
        }

        private void terminate() {
            if (child != null && status != TERMINATING) {
                child.destroy();
                status = TERMINATING;
                waitTime = System.currentTimeMillis() + groupTimeout;
                notifyAll();
            }
        }

       /*
        * Fallthrough from TERMINATE to TERMINATING
        * is intentional
        */
        @SuppressWarnings("fallthrough")
        private void await() {
            while (true) {
                switch (status) {
                case NORMAL:
                    return;
                case TERMINATE:
                    terminate();
                case TERMINATING:
                    try {
                        child.exitValue();
                    } catch (IllegalThreadStateException e) {
                        long now = System.currentTimeMillis();
                        if (waitTime > now) {
                            try {
                                wait(waitTime - now);
                            } catch (InterruptedException ee) {
                            }
                            continue;
                        }
                        // REMIND: print message that group did not terminate?
                    }
                    childGone();
                    return;
                case CREATING:
                    try {
                        wait();
                    } catch (InterruptedException e) {
                    }
                }
            }
        }

        synchronized void shutdown() {
            reset();
            terminate();
            await();
        }

        MarshalledObject<? extends Remote> activate(ActivationID id,
                                                    boolean force)
            throws ActivationException
        {
            Exception detail = null;

            /*
             * Attempt to activate object and reattempt (several times)
             * if activation fails due to communication problems.
             */
            for (int tries = MAX_TRIES; tries > 0; tries--) {
                ActivationInstantiator inst;
                long currentIncarnation;

                // look up object to activate
                ObjectEntry objEntry;
                synchronized (this) {
                    objEntry = getObjectEntry(id);
                    // if not forcing activation, return cached stub
                    if (!force && objEntry.stub != null) {
                        return objEntry.stub;
                    }
                    inst = getInstantiator(groupID);
                    currentIncarnation = incarnation;
                }

                boolean groupInactive = false;
                boolean failure = false;
                // activate object
                try {
                    return objEntry.activate(id, force, inst);
                } catch (NoSuchObjectException e) {
                    groupInactive = true;
                    detail = e;
                } catch (ConnectException e) {
                    groupInactive = true;
                    failure = true;
                    detail = e;
                } catch (ConnectIOException e) {
                    groupInactive = true;
                    failure = true;
                    detail = e;
                } catch (InactiveGroupException e) {
                    groupInactive = true;
                    detail = e;
                } catch (RemoteException e) {
                    // REMIND: wait some here before continuing?
                    if (detail == null) {
                        detail = e;
                    }
                }

                if (groupInactive) {
                    // group has failed or is inactive; mark inactive
                    try {
                        System.err.println(
                            MessageFormat.format(
                                getTextResource("rmid.group.inactive"),
                                detail.toString()));
                        detail.printStackTrace();
                        getGroupEntry(groupID).
                            inactiveGroup(currentIncarnation, failure);
                    } catch (UnknownGroupException e) {
                        // not a problem
                    }
                }
            }

            /**
             * signal that group activation failed, nested exception
             * specifies what exception occurred when the group did not
             * activate
             */
            throw new ActivationException("object activation failed after " +
                                          MAX_TRIES + " tries", detail);
        }

        /**
         * Returns the instantiator for the group specified by id and
         * entry. If the group is currently inactive, exec some
         * bootstrap code to create the group.
         */
        private ActivationInstantiator getInstantiator(ActivationGroupID id)
            throws ActivationException
        {
            assert Thread.holdsLock(this);

            await();
            if (group != null) {
                return group;
            }
            checkRemoved();
            boolean acquired = false;

            try {
                groupName = Pstartgroup();
                acquired = true;
                String[] argv = activationArgs(desc);
                checkArgs(desc, argv);

                if (debugExec) {
                    StringBuffer sb = new StringBuffer(argv[0]);
                    int j;
                    for (j = 1; j < argv.length; j++) {
                        sb.append(' ');
                        sb.append(argv[j]);
                    }
                    System.err.println(
                        MessageFormat.format(
                            getTextResource("rmid.exec.command"),
                            sb.toString()));
                }

                try {
                    child = Runtime.getRuntime().exec(argv);
                    status = CREATING;
                    ++incarnation;
                    watchdog = new Watchdog();
                    watchdog.start();

                    // handle child I/O streams before writing to child
                    PipeWriter.plugTogetherPair
                        (child.getInputStream(), System.out,
                         child.getErrorStream(), System.err);
                    try (MarshalOutputStream out =
                            new MarshalOutputStream(child.getOutputStream())) {
                        out.writeObject(id);
                        out.writeObject(desc);
                        out.writeLong(incarnation);
                        out.flush();
                    }


                } catch (IOException e) {
                    terminate();
                    throw new ActivationException(
                        "unable to create activation group", e);
                }

                try {
                    long now = System.currentTimeMillis();
                    long stop = now + execTimeout;
                    do {
                        wait(stop - now);
                        if (group != null) {
                            return group;
                        }
                        now = System.currentTimeMillis();
                    } while (status == CREATING && now < stop);
                } catch (InterruptedException e) {
                }

                terminate();
                throw new ActivationException(
                        (removed ?
                         "activation group unregistered" :
                         "timeout creating child process"));
            } finally {
                if (acquired) {
                    Vstartgroup();
                }
            }
        }

        /**
         * Waits for process termination and then restarts services.
         */
        private class Watchdog extends Thread {
            private final Process groupProcess = child;
            private final long groupIncarnation = incarnation;
            private boolean canInterrupt = true;
            private boolean shouldQuit = false;
            private boolean shouldRestart = true;

            Watchdog() {
                super("WatchDog-"  + groupName + "-" + incarnation);
                setDaemon(true);
            }

            public void run() {

                if (shouldQuit) {
                    return;
                }

                /*
                 * Wait for the group to crash or exit.
                 */
                try {
                    groupProcess.waitFor();
                } catch (InterruptedException exit) {
                    return;
                }

                boolean restart = false;
                synchronized (GroupEntry.this) {
                    if (shouldQuit) {
                        return;
                    }
                    canInterrupt = false;
                    interrupted(); // clear interrupt bit
                    /*
                     * Since the group crashed, we should
                     * reset the entry before activating objects
                     */
                    if (groupIncarnation == incarnation) {
                        restart = shouldRestart && !shuttingDown;
                        reset();
                        childGone();
                    }
                }

                /*
                 * Activate those objects that require restarting
                 * after a crash.
                 */
                if (restart) {
                    restartServices();
                }
            }

            /**
             * Marks this thread as one that is no longer needed.
             * If the thread is in a state in which it can be interrupted,
             * then the thread is interrupted.
             */
            void dispose() {
                shouldQuit = true;
                if (canInterrupt) {
                    interrupt();
                }
            }

            /**
             * Marks this thread as no longer needing to restart objects.
             */
            void noRestart() {
                shouldRestart = false;
            }
        }
    }

    private String[] activationArgs(ActivationGroupDesc desc) {
        ActivationGroupDesc.CommandEnvironment cmdenv;
        cmdenv = desc.getCommandEnvironment();

        // argv is the literal command to exec
        List<String> argv = new ArrayList<>();

        // Command name/path
        argv.add((cmdenv != null && cmdenv.getCommandPath() != null)
                    ? cmdenv.getCommandPath()
                    : command[0]);

        // Group-specific command options
        if (cmdenv != null && cmdenv.getCommandOptions() != null) {
            argv.addAll(Arrays.asList(cmdenv.getCommandOptions()));
        }

        // Properties become -D parameters
        Properties props = desc.getPropertyOverrides();
        if (props != null) {
            for (Enumeration<?> p = props.propertyNames();
                 p.hasMoreElements();)
            {
                String name = (String) p.nextElement();
                /* Note on quoting: it would be wrong
                 * here, since argv will be passed to
                 * Runtime.exec, which should not parse
                 * arguments or split on whitespace.
                 */
                argv.add("-D" + name + "=" + props.getProperty(name));
            }
        }

        /* Finally, rmid-global command options (e.g. -C options)
         * and the classname
         */
        for (int i = 1; i < command.length; i++) {
            argv.add(command[i]);
        }

        String[] realArgv = new String[argv.size()];
        System.arraycopy(argv.toArray(), 0, realArgv, 0, realArgv.length);

        return realArgv;
    }

    private void checkArgs(ActivationGroupDesc desc, String[] cmd)
        throws SecurityException, ActivationException
    {
        /*
         * Check exec command using execPolicy object
         */
        if (execPolicyMethod != null) {
            if (cmd == null) {
                cmd = activationArgs(desc);
            }
            try {
                execPolicyMethod.invoke(execPolicy, desc, cmd);
            } catch (InvocationTargetException e) {
                Throwable targetException = e.getTargetException();
                if (targetException instanceof SecurityException) {
                    throw (SecurityException) targetException;
                } else {
                    throw new ActivationException(
                        execPolicyMethod.getName() + ": unexpected exception",
                        e);
                }
            } catch (Exception e) {
                throw new ActivationException(
                    execPolicyMethod.getName() + ": unexpected exception", e);
            }
        }
    }

    private static class ObjectEntry implements Serializable {

        private static final long serialVersionUID = -5500114225321357856L;

        /** descriptor for object */
        ActivationDesc desc;
        /** the stub (if active) */
        volatile transient MarshalledObject<? extends Remote> stub = null;
        volatile transient boolean removed = false;

        ObjectEntry(ActivationDesc desc) {
            this.desc = desc;
        }

        synchronized MarshalledObject<? extends Remote>
            activate(ActivationID id,
                     boolean force,
                     ActivationInstantiator inst)
            throws RemoteException, ActivationException
        {
            MarshalledObject<? extends Remote> nstub = stub;
            if (removed) {
                throw new UnknownObjectException("object removed");
            } else if (!force && nstub != null) {
                return nstub;
            }

            nstub = inst.newInstance(id, desc);
            stub = nstub;
            /*
             * stub could be set to null by a group reset, so return
             * the newstub here to prevent returning null.
             */
            return nstub;
        }

        void reset() {
            stub = null;
        }
    }

   

    /**
     * Abstract class for all log records. The subclass contains
     * specific update information and implements the apply method
     * that applys the update information contained in the record
     * to the current state.
     */
    private static abstract class LogRecord implements Serializable {
        /** indicate compatibility with JDK 1.2 version of class */
        private static final long serialVersionUID = 8395140512322687529L;
        abstract Object apply(Object state) throws Exception;
    }

    private static void bomb(String error) {
        System.err.println("rmid: " + error); // $NON-NLS$
        System.err.println(MessageFormat.format(getTextResource("rmid.usage"),
                    "rmid"));
        System.exit(1);
    }

    /**
     * The default policy for checking a command before it is executed
     * makes sure the appropriate com.sun.rmi.rmid.ExecPermission and
     * set of com.sun.rmi.rmid.ExecOptionPermissions have been granted.
     */
    public static class DefaultExecPolicy {

        public void checkExecCommand(ActivationGroupDesc desc, String[] cmd)
            throws SecurityException
        {
            PermissionCollection perms = getExecPermissions();

            /*
             * Check properties overrides.
             */
            Properties props = desc.getPropertyOverrides();
            if (props != null) {
                Enumeration<?> p = props.propertyNames();
                while (p.hasMoreElements()) {
                    String name = (String) p.nextElement();
                    String value = props.getProperty(name);
                    String option = "-D" + name + "=" + value;
                    try {
                        checkPermission(perms,
                            new ExecOptionPermission(option));
                    } catch (AccessControlException e) {
                        if (value.equals("")) {
                            checkPermission(perms,
                                new ExecOptionPermission("-D" + name));
                        } else {
                            throw e;
                        }
                    }
                }
            }

            /*
             * Check group class name (allow nothing but the default),
             * code location (must be null), and data (must be null).
             */
            String groupClassName = desc.getClassName();
            if ((groupClassName != null &&
                 !groupClassName.equals(
                    ActivationGroupImpl.class.getName())) ||
                (desc.getLocation() != null) ||
                (desc.getData() != null))
            {
                throw new AccessControlException(
                    "access denied (custom group implementation not allowed)");
            }

            /*
             * If group descriptor has a command environment, check
             * command and options.
             */
            ActivationGroupDesc.CommandEnvironment cmdenv;
            cmdenv = desc.getCommandEnvironment();
            if (cmdenv != null) {
                String path = cmdenv.getCommandPath();
                if (path != null) {
                    checkPermission(perms, new ExecPermission(path));
                }

                String[] options = cmdenv.getCommandOptions();
                if (options != null) {
                    for (String option : options) {
                        checkPermission(perms,
                                        new ExecOptionPermission(option));
                    }
                }
            }
        }

     

        private static PermissionCollection getExecPermissions() {
            /*
             * The approach used here is taken from the similar method
             * getLoaderAccessControlContext() in the class
             * sun.rmi.server.LoaderHandler.
             */

            // obtain permissions granted to all code in current policy
            PermissionCollection perms = AccessController.doPrivileged(
                new PrivilegedAction<PermissionCollection>() {
                    public PermissionCollection run() {
                        CodeSource codesource =
                            new CodeSource(null, (Certificate[]) null);
                        Policy p = Policy.getPolicy();
                        if (p != null) {
                            return p.getPermissions(codesource);
                        } else {
                            return new Permissions();
                        }
                    }
                });

            return perms;
        }

        private static void checkPermission(PermissionCollection perms,
                                            Permission p)
            throws AccessControlException
        {
            if (!perms.implies(p)) {
                throw new AccessControlException(
                   "access denied " + p.toString());
            }
        }
    }

    /**
     * Main program to start the activation system. <br>
     * The usage is as follows: rmid [-port num] [-log dir].
     */
    public static void main(String[] args) {
        boolean stop = false;

        // Create and install the security manager if one is not installed
        // already.
        if (System.getSecurityManager() == null) {
            System.setSecurityManager(new SecurityManager());
        }

        try {
            int port = ActivationSystem.SYSTEM_PORT;
            RMIServerSocketFactory ssf = null;

            /*
             * If rmid has an inherited channel (meaning that it was
             * launched from inetd), set the server socket factory to
             * return the inherited server socket.
             **/
            Channel inheritedChannel = AccessController.doPrivileged(
                new PrivilegedExceptionAction<Channel>() {
                    public Channel run() throws IOException {
                        return System.inheritedChannel();
                    }
                });

            if (inheritedChannel != null &&
                inheritedChannel instanceof ServerSocketChannel)
            {
                /*
                 * Redirect System.err output to a file.
                 */
                AccessController.doPrivileged(
                    new PrivilegedExceptionAction<Void>() {
                        public Void run() throws IOException {
                            File file =
                                Files.createTempFile("rmid-err", null).toFile();
                            PrintStream errStream =
                                new PrintStream(new FileOutputStream(file));
                            System.setErr(errStream);
                            return null;
                        }
                    });

                ServerSocket serverSocket =
                    ((ServerSocketChannel) inheritedChannel).socket();
                port = serverSocket.getLocalPort();
                ssf = new ActivationServerSocketFactory(serverSocket);

                System.err.println(new Date());
                System.err.println(getTextResource(
                                       "rmid.inherited.channel.info") +
                                       ": " + inheritedChannel);
            }

            String log = null;
            List<String> childArgs = new ArrayList<>();

            /*
             * Parse arguments
             */
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("-port")) {
                    if (ssf != null) {
                        bomb(getTextResource("rmid.syntax.port.badarg"));
                    }
                    if ((i + 1) < args.length) {
                        try {
                            port = Integer.parseInt(args[++i]);
                        } catch (NumberFormatException nfe) {
                            bomb(getTextResource("rmid.syntax.port.badnumber"));
                        }
                    } else {
                        bomb(getTextResource("rmid.syntax.port.missing"));
                    }

                } else if (args[i].equals("-log")) {
                    if ((i + 1) < args.length) {
                        log = args[++i];
                    } else {
                        bomb(getTextResource("rmid.syntax.log.missing"));
                    }

                } else if (args[i].equals("-stop")) {
                    stop = true;

                } else if (args[i].startsWith("-C")) {
                    childArgs.add(args[i].substring(2));

                } else {
                    bomb(MessageFormat.format(
                        getTextResource("rmid.syntax.illegal.option"),
                        args[i]));
                }
            }

            if (log == null) {
                if (ssf != null) {
                    bomb(getTextResource("rmid.syntax.log.required"));
                } else {
                    log = "log";
                }
            }

            debugExec = AccessController.doPrivileged(
                new GetBooleanAction("sun.rmi.server.activation.debugExec"));

            /**
             * Determine class name for activation exec policy (if any).
             */
            String execPolicyClassName = AccessController.doPrivileged(
                new GetPropertyAction("sun.rmi.activation.execPolicy", null));
            if (execPolicyClassName == null) {
                if (!stop) {
                }
                execPolicyClassName = "default";
            }

            /**
             * Initialize method for activation exec policy.
             */
            if (!execPolicyClassName.equals("none")) {
                if (execPolicyClassName.equals("") ||
                    execPolicyClassName.equals("default"))
                {
                    execPolicyClassName = DefaultExecPolicy.class.getName();
                }

                try {
                    Class<?> execPolicyClass = getRMIClass(execPolicyClassName);
                    execPolicy = execPolicyClass.newInstance();
                    execPolicyMethod =
                        execPolicyClass.getMethod("checkExecCommand",
                                                  ActivationGroupDesc.class,
                                                  String[].class);
                } catch (Exception e) {
                    if (debugExec) {
                        System.err.println(
                            getTextResource("rmid.exec.policy.exception"));
                        e.printStackTrace();
                    }
                    bomb(getTextResource("rmid.exec.policy.invalid"));
                }
            }

            if (stop == true) {
                final int finalPort = port;
                AccessController.doPrivileged(new PrivilegedAction<Void>() {
                    public Void run() {
                        System.setProperty("java.rmi.activation.port",
                                           Integer.toString(finalPort));
                        return null;
                    }
                });
                ActivationSystem system = ActivationGroup.getSystem();
                system.shutdown();
                System.exit(0);
            }

            // prevent activator from exiting
            while (true) {
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException e) {
                }
            }
        } catch (Exception e) {
            System.err.println(
                MessageFormat.format(
                    getTextResource("rmid.unexpected.exception"), e));
            e.printStackTrace();
        }
        System.exit(1);
    }

    /**
     * Retrieves text resources from the locale-specific properties file.
     */
    private static String getTextResource(String key) {
        if (Activation.resources == null) {
            try {
                Activation.resources = ResourceBundle.getBundle(
                    "sun.rmi.server.resources.rmid");
            } catch (MissingResourceException mre) {
            }
            if (Activation.resources == null) {
                // throwing an Error is a bit extreme, methinks
                return ("[missing resource file: " + key + "]");
            }
        }

        String val = null;
        try {
            val = Activation.resources.getString (key);
        } catch (MissingResourceException mre) {
        }

        if (val == null) {
            return ("[missing resource: " + key + "]");
        } else {
            return val;
        }
    }

    @SuppressWarnings("deprecation")
    private static Class<?> getRMIClass(String execPolicyClassName) throws Exception  {
        return RMIClassLoader.loadClass(execPolicyClassName);
    }
    /*
     * Dijkstra semaphore operations to limit the number of subprocesses
     * rmid attempts to make at once.
     */
    /**
     * Acquire the group semaphore and return a group name.  Each
     * Pstartgroup must be followed by a Vstartgroup.  The calling thread
     * will wait until there are fewer than <code>N</code> other threads
     * holding the group semaphore.  The calling thread will then acquire
     * the semaphore and return.
     */
    private synchronized String Pstartgroup() throws ActivationException {
        while (true) {
            checkShutdown();
            // Wait until positive, then decrement.
            if (groupSemaphore > 0) {
                groupSemaphore--;
                return "Group-" + groupCounter++;
            }

            try {
                wait();
            } catch (InterruptedException e) {
            }
        }
    }

    /**
     * Release the group semaphore.  Every P operation must be
     * followed by a V operation.  This may cause another thread to
     * wake up and return from its P operation.
     */
    private synchronized void Vstartgroup() {
        // Increment and notify a waiter (not necessarily FIFO).
        groupSemaphore++;
        notifyAll();
    }

    /**
     * A server socket factory to use when rmid is launched via 'inetd'
     * with 'wait' status.  This socket factory's 'createServerSocket'
     * method returns the server socket specified during construction that
     * is specialized to delay accepting requests until the
     * 'initDone' flag is 'true'.  The server socket supplied to
     * the constructor should be the server socket obtained from the
     * ServerSocketChannel returned from the 'System.inheritedChannel'
     * method.
     **/
    private static class ActivationServerSocketFactory
        implements RMIServerSocketFactory
    {
        private final ServerSocket serverSocket;

        /**
         * Constructs an 'ActivationServerSocketFactory' with the specified
         * 'serverSocket'.
         **/
        ActivationServerSocketFactory(ServerSocket serverSocket) {
            this.serverSocket = serverSocket;
        }

        /**
         * Returns the server socket specified during construction wrapped
         * in a 'DelayedAcceptServerSocket'.
         **/
        public ServerSocket createServerSocket(int port)
            throws IOException
        {
            return new DelayedAcceptServerSocket(serverSocket);
        }

    }

    /**
     * A server socket that delegates all public methods to the underlying
     * server socket specified at construction.  The accept method is
     * overridden to delay calling accept on the underlying server socket
     * until the 'initDone' flag is 'true'.
     **/
    private static class DelayedAcceptServerSocket extends ServerSocket {

        private final ServerSocket serverSocket;

        DelayedAcceptServerSocket(ServerSocket serverSocket)
            throws IOException
        {
            this.serverSocket = serverSocket;
        }

        public void bind(SocketAddress endpoint) throws IOException {
            serverSocket.bind(endpoint);
        }

        public void bind(SocketAddress endpoint, int backlog)
                throws IOException
        {
            serverSocket.bind(endpoint, backlog);
        }

        public InetAddress getInetAddress() {
            return AccessController.doPrivileged(
                new PrivilegedAction<InetAddress>() {
                    @Override
                    public InetAddress run() {
                        return serverSocket.getInetAddress();
                    }
                });
        }

        public int getLocalPort() {
            return serverSocket.getLocalPort();
        }

        public SocketAddress getLocalSocketAddress() {
            return AccessController.doPrivileged(
                new PrivilegedAction<SocketAddress>() {
                    @Override
                    public SocketAddress run() {
                        return serverSocket.getLocalSocketAddress();
                    }
                });
        }

        /**
         * Delays calling accept on the underlying server socket until the
         * remote service is bound in the registry.
         **/
        public Socket accept() throws IOException {
            synchronized (initLock) {
                try {
                    while (!initDone) {
                        initLock.wait();
                    }
                } catch (InterruptedException ignore) {
                    throw new AssertionError(ignore);
                }
            }
            return serverSocket.accept();
        }

        public void close() throws IOException {
            serverSocket.close();
        }

        public ServerSocketChannel getChannel() {
            return serverSocket.getChannel();
        }

        public boolean isBound() {
            return serverSocket.isBound();
        }

        public boolean isClosed() {
            return serverSocket.isClosed();
        }

        public void setSoTimeout(int timeout)
            throws SocketException
        {
            serverSocket.setSoTimeout(timeout);
        }

        public int getSoTimeout() throws IOException {
            return serverSocket.getSoTimeout();
        }

        public void setReuseAddress(boolean on) throws SocketException {
            serverSocket.setReuseAddress(on);
        }

        public boolean getReuseAddress() throws SocketException {
            return serverSocket.getReuseAddress();
        }

        public String toString() {
            return serverSocket.toString();
        }

        public void setReceiveBufferSize(int size)
            throws SocketException
        {
            serverSocket.setReceiveBufferSize(size);
        }

        public int getReceiveBufferSize()
            throws SocketException
        {
            return serverSocket.getReceiveBufferSize();
        }
    }
}

/**
 * PipeWriter plugs together two pairs of input and output streams by
 * providing readers for input streams and writing through to
 * appropriate output streams.  Both output streams are annotated on a
 * per-line basis.
 *
 * @author Laird Dornin, much code borrowed from Peter Jones, Ken
 *         Arnold and Ann Wollrath.
 */
class PipeWriter implements Runnable {

    /** stream used for buffering lines */
    private ByteArrayOutputStream bufOut;

    /** count since last separator */
    private int cLast;

    /** current chunk of input being compared to lineSeparator.*/
    private byte[] currSep;

    private PrintWriter out;
    private InputStream in;

    private String pipeString;
    private String execString;

    private static String lineSeparator;
    private static int lineSeparatorLength;

    private static int numExecs = 0;

    static {
        lineSeparator = AccessController.doPrivileged(
            new GetPropertyAction("line.separator"));
        lineSeparatorLength = lineSeparator.length();
    }

    /**
     * Create a new PipeWriter object. All methods of PipeWriter,
     * except plugTogetherPair, are only accesible to PipeWriter
     * itself.  Synchronization is unnecessary on functions that will
     * only be used internally in PipeWriter.
     *
     * @param in input stream from which pipe input flows
     * @param out output stream to which log messages will be sent
     * @param dest String which tags output stream as 'out' or 'err'
     * @param nExecs number of execed processes, Activation groups.
     */
    private PipeWriter
        (InputStream in, OutputStream out, String tag, int nExecs) {

        this.in = in;
        this.out = new PrintWriter(out);

        bufOut = new ByteArrayOutputStream();
        currSep = new byte[lineSeparatorLength];

        /* set unique pipe/pair annotations */
        execString = ":ExecGroup-" +
            Integer.toString(nExecs) + ':' + tag + ':';
    }

    /**
     * Create a thread to listen and read from input stream, in.  buffer
     * the data that is read until a marker which equals lineSeparator
     * is read.  Once such a string has been discovered; write out an
     * annotation string followed by the buffered data and a line
     * separator.
     */
    public void run() {
        byte[] buf = new byte[256];
        int count;

        try {
            /* read bytes till there are no more. */
            while ((count = in.read(buf)) != -1) {
                write(buf, 0, count);
            }

            /*  flush internal buffer... may not have ended on a line
             *  separator, we also need a last annotation if
             *  something was left.
             */
            String lastInBuffer = bufOut.toString();
            bufOut.reset();
            if (lastInBuffer.length() > 0) {
                out.println (createAnnotation() + lastInBuffer);
                out.flush();                    // add a line separator
                                                // to make output nicer
            }

        } catch (IOException e) {
        }
    }

    /**
     * Write a subarray of bytes.  Pass each through write byte method.
     */
    private void write(byte b[], int off, int len) throws IOException {

        if (len < 0) {
            throw new ArrayIndexOutOfBoundsException(len);
        }
        for (int i = 0; i < len; ++ i) {
            write(b[off + i]);
        }
    }

    /**
     * Write a byte of data to the stream.  If we have not matched a
     * line separator string, then the byte is appended to the internal
     * buffer.  If we have matched a line separator, then the currently
     * buffered line is sent to the output writer with a prepended
     * annotation string.
     */
    private void write(byte b) throws IOException {
        int i = 0;

        /* shift current to the left */
        for (i = 1 ; i < (currSep.length); i ++) {
            currSep[i-1] = currSep[i];
        }
        currSep[i-1] = b;
        bufOut.write(b);

        /* enough characters for a separator? */
        if ( (cLast >= (lineSeparatorLength - 1)) &&
             (lineSeparator.equals(new String(currSep))) ) {

            cLast = 0;

            /* write prefix through to underlying byte stream */
            out.print(createAnnotation() + bufOut.toString());
            out.flush();
            bufOut.reset();

            if (out.checkError()) {
                throw new IOException
                    ("PipeWriter: IO Exception when"+
                     " writing to output stream.");
            }

        } else {
            cLast++;
        }
    }

    /**
     * Create an annotation string to be printed out after
     * a new line and end of stream.
     */
    private String createAnnotation() {

        /* construct prefix for log messages:
         * date/time stamp...
         */
        return ((new Date()).toString()  +
                 /* ... print pair # ... */
                 (execString));
    }

    /**
     * Allow plugging together two pipes at a time, to associate
     * output from an execed process.  This is the only publicly
     * accessible method of this object; this helps ensure that
     * synchronization will not be an issue in the annotation
     * process.
     *
     * @param in input stream from which pipe input comes
     * @param out output stream to which log messages will be sent
     * @param in1 input stream from which pipe input comes
     * @param out1 output stream to which log messages will be sent
     */
    static void plugTogetherPair(InputStream in,
                                 OutputStream out,
                                 InputStream in1,
                                 OutputStream out1) {
        Thread inThread = null;
        Thread outThread = null;

        int nExecs = getNumExec();

        /* start RMI threads to read output from child process */
        inThread = AccessController.doPrivileged(
            new NewThreadAction(new PipeWriter(in, out, "out", nExecs),
                                "out", true));
        outThread = AccessController.doPrivileged(
            new NewThreadAction(new PipeWriter(in1, out1, "err", nExecs),
                                "err", true));
        inThread.start();
        outThread.start();
    }

    private static synchronized int getNumExec() {
        return numExecs++;
    }
}
