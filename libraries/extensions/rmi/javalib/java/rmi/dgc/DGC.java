/*
 * Copyright (c) 1996, 1997, 1998, 1999
 *      Transvirtual Technologies, Inc.  All rights reserved.
 *
 * See the file "license-lesser.terms" for information on usage and 
 * redistribution of this file.
 */

package java.rmi.dgc;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.server.ObjID;

public interface DGC
	extends Remote {

    Lease dirty(ObjID[] ids, long sequenceNum, Lease lease) throws RemoteException;

    void clean(ObjID[] ids, long sequenceNum, VMID vmid, boolean strong) throws RemoteException;

}
