/*******************************************************************************
    Copyright 2008 Sun Microsystems, Inc.,
    4150 Network Circle, Santa Clara, California 95054, U.S.A.
    All rights reserved.

    U.S. Government Rights - Commercial software.
    Government users are subject to the Sun Microsystems, Inc. standard
    license agreement and applicable provisions of the FAR and its supplements.

    Use is subject to license terms.

    This distribution may include materials developed by third parties.

    Sun, Sun Microsystems, the Sun logo and Java are trademarks or registered
    trademarks of Sun Microsystems, Inc. in the U.S. and other countries.
******************************************************************************/

package com.sun.fortress.interpreter.evaluator.transactions;

import com.sun.fortress.exceptions.transactions.PanicException;
import com.sun.fortress.exceptions.transactions.OrphanedException;
import com.sun.fortress.interpreter.evaluator.tasks.FortressTaskRunner;
import com.sun.fortress.interpreter.evaluator.values.FValue;
import com.sun.fortress.interpreter.evaluator.values.FInt;
import com.sun.fortress.interpreter.env.ReferenceCell;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Transaction.java
 * Keeps a transaction's status and contention manager.
 */

public class Transaction {

    /**
     * Possible transaction status
     **/
    private enum Status {ORPHANED, ABORTED, ACTIVE, COMMITTED};
    private Transaction parent;
    private List<Transaction> children;
    /** Updater for status */
    private volatile AtomicReference<Status> myStatus;
    private ContentionManager manager;
    private int nestingDepth;
    private long threadID;
    private int count;

	// Used for debugging
    private static AtomicInteger counter = new AtomicInteger();
    public static boolean debug = false;
    private ConcurrentHashMap<ReferenceCell, ConcurrentHashMap<FortressTaskRunner, String>> updates;


    /**
     * Creates a new, active transaction.
     */
    public Transaction() {
		myStatus = new AtomicReference(Status.ACTIVE);
		manager = FortressTaskRunner.getContentionManager();
		threadID = Thread.currentThread().getId();
		parent = null;
		children = new ArrayList<Transaction>();
		nestingDepth = 0;
		if (debug) {
			count = counter.getAndIncrement();
    		updates = new ConcurrentHashMap<ReferenceCell, ConcurrentHashMap<FortressTaskRunner, String>>();
		} else {
			count = 0;
		}
    }

    public Transaction(Transaction p) {
		if (p.isActive()) {
			myStatus = new AtomicReference(Status.ACTIVE);
			manager = FortressTaskRunner.getContentionManager();
			if (p != null) {
				p.addChild(this);
			}
			parent = p;
			children = new ArrayList<Transaction>();
			if (p != null)
				nestingDepth = p.getNestingDepth() + 1;
			else nestingDepth = 0;
			if (debug) {
				updates = new ConcurrentHashMap<ReferenceCell, ConcurrentHashMap<FortressTaskRunner, String>>();
				count = counter.getAndIncrement();
			} else {
				count = 0;
			}
		} else {
			myStatus = new AtomicReference(Status.ORPHANED);
		}

    }

	// Used for debugging, called from ReferenceCell
    public void addRead(ReferenceCell rc, FValue f) { 
		FortressTaskRunner runner = (FortressTaskRunner) Thread.currentThread();
		updates.putIfAbsent(rc, new ConcurrentHashMap<FortressTaskRunner, String>());
		ConcurrentHashMap<FortressTaskRunner, String> m = updates.get(rc);
		if (!m.containsKey(runner)) {
			m.put(runner, "(read " + f + ")");
		} else {
			m.put(runner, m.get(runner) + "(read " + f + ")");
		}
	}

	// Used for debugging, called from ReferenceCell
    public void addWrite(ReferenceCell rc, FValue f) { 	
		FortressTaskRunner runner = (FortressTaskRunner) Thread.currentThread();
		updates.putIfAbsent(rc, new ConcurrentHashMap<FortressTaskRunner, String>());
		ConcurrentHashMap<FortressTaskRunner, String> m = updates.get(rc);
		if (!m.containsKey(runner)) {
			m.put(runner, "( write " + f + ")");
		} else {
			String temp = m.get(runner) + "(write " + f + ")";
			m.put(runner, temp);
		}
	}
	// Used for debugging, called from ReferenceCell
    public void mergeUpdates(String s, Transaction t, ReferenceCell rc) {
		FortressTaskRunner runner = (FortressTaskRunner) Thread.currentThread();
		updates.putIfAbsent(rc, new ConcurrentHashMap<FortressTaskRunner,String>());
		ConcurrentHashMap<FortressTaskRunner, String> m = updates.get(rc);
		if (!m.containsKey(runner))
			m.put(runner,  s  );
		else
			m.put(runner, m.get(runner) +  s );
	}

    public int getNestingDepth() { return nestingDepth;}
    public long getThreadId() { return threadID;}


    /* Access the transaction's current status.
     * @return current transaction status
     */
    private Status getStatus() {
		return myStatus.get();
    }

    /**
     * Tests whether transaction is active.
     * @return whether transaction is active
     */
    public boolean isActive() {
		return getStatus() == Status.ACTIVE;
    }

    /**
     * Tests whether transaction is aborted.
     * @return whether transaction is aborted
     */
    public boolean isAborted() {
		return getStatus() == Status.ABORTED;
    }

    /**
     * Tests whether transaction is committed.
     * @return whether transaction is committed
     */
    public boolean isCommitted() {
		return getStatus() == Status.COMMITTED;
    }

    /**
     * Tests whether transaction is abandoned
     * @return whether transaction is abandoned
     */

    public boolean isOrphaned() {
		return getStatus() == Status.ORPHANED;
    }

    public List<Transaction> getChildren() { return children;}

    public boolean addChild(Transaction c) { 
		if (isActive()) {
			synchronized (children) {
				children.add(c);
			}
			return true;
		} else 
			return false;
    }
    
    public Transaction getParent() { return parent;}

    /**
     * Tests whether transaction is committed or active.
     * @return whether transaction is committed or active
     */
    public boolean validate() {
		Status st = getStatus();
	
		switch (st) {
		case COMMITTED:
			throw new PanicException("committed transaction still running");
		case ACTIVE:
			return true;
		case ABORTED:
			return false;
		case ORPHANED:
			return false;
		default:
			throw new PanicException("unexpected transaction state: " + getStatus());
		}
    }

    /**
     * Tries to commit transaction
     * @return whether transaction was committed
     */
    public boolean commit() {
		if (myStatus.compareAndSet(Status.ACTIVE, Status.COMMITTED)) {
			if (debug) {
				if (parent == null) {
					Enumeration<ReferenceCell> temp = updates.keys();
					while (temp.hasMoreElements()) {
						ReferenceCell key = temp.nextElement();
						ConcurrentHashMap<FortressTaskRunner, String> ups = updates.get(key);
						FortressTaskRunner runner = (FortressTaskRunner) Thread.currentThread();
						String mine = ups.get(runner);
					}
				} else {
					Enumeration<ReferenceCell> temp = updates.keys();
					while (temp.hasMoreElements()) {
						ReferenceCell key = temp.nextElement();
						ConcurrentHashMap<FortressTaskRunner, String> ups = updates.get(key);
						FortressTaskRunner runner = (FortressTaskRunner) Thread.currentThread();
						String mine = ups.get(runner);				
						parent.mergeUpdates(mine, this, key);
					}
				}
			}
			return true;
		}
		return false;
	}

    /**
     * Tries to abort transaction
     * @return whether transaction was aborted (not necessarily by this call)
     */
    public boolean abort() {
		if (myStatus.compareAndSet(Status.ACTIVE, Status.ABORTED)) {
			synchronized(children ) {
				for (Transaction child : getChildren())
					child.orphan();
			}
			return true;
		} else {
			if (isActive())
				throw new RuntimeException("Transaction " + this + " is active and didn't get aborted ?");
			return false;
		}
    }

    public boolean orphan() {
		if (myStatus.compareAndSet(Status.ACTIVE, Status.ORPHANED)) {
			synchronized(children ) {
				for (Transaction child : getChildren())
					child.orphan();
			}
			throw new OrphanedException(this, "I'm an orphan, so my kids are too");
		} else if (myStatus.compareAndSet(Status.ABORTED, Status.ORPHANED)) {
			synchronized(children ) {
				for (Transaction child : getChildren())
					child.orphan();
			}
			return false;
		} else if (myStatus.compareAndSet(Status.COMMITTED, Status.ORPHANED)) {
			synchronized(children ) {
				for (Transaction child : getChildren())
					child.orphan();
			}
			return false;
		} else return false;
    }
	
    
    /**
     * Returns a string representation of this transaction
     * @return the string representcodes[ation
     */
    public String toString() {
		switch (getStatus()) {
		case COMMITTED:
			return "[T" + count + ":committed, p=" + getParent() + "=>" + getNestingDepth() + "]";
		case ABORTED:
			return "[T" + count + ":aborted,p=" + getParent() + "=>" + getNestingDepth() + "]";
		case ACTIVE:
			return "[T" + count + ":active,p=" + getParent() + "=>" + getNestingDepth() + "]";
		case ORPHANED:
			return "[T" + count + ":orphaned ]";
		default:
			return "[T" + count + "[???]]";
		}
    }

    /**
     * This transaction's contention manager
     * @return the manager
     */
    public ContentionManager getContentionManager() {
		return manager;
    }

    public boolean isAncestorOf(Transaction t) {
		Transaction current = t;
		while (current != null && current != this) 
			current = current.getParent();
		if (current == this) {
			return true; 
		} else {
			return false;
		}
    }
}
