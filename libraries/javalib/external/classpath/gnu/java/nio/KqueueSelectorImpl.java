/* KqueueSelectorImpl.java -- Selector for systems with kqueue event notification.
   Copyright (C) 2006 Free Software Foundation, Inc.

This file is part of GNU Classpath.

GNU Classpath is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2, or (at your option)
any later version.

GNU Classpath is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License
along with GNU Classpath; see the file COPYING.  If not, write to the
Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
02110-1301 USA.

Linking this library statically or dynamically with other modules is
making a combined work based on this library.  Thus, the terms and
conditions of the GNU General Public License cover the whole
combination.

As a special exception, the copyright holders of this library give you
permission to link this library with independent modules to produce an
executable, regardless of the license terms of these independent
modules, and to copy and distribute the resulting executable under
terms of your choice, provided that you also meet, for each linked
independent module, the terms and conditions of the license of that
module.  An independent module is a module which is not derived from
or based on this library.  If you modify this library, you may extend
this exception to your version of the library, but you are not
obligated to do so.  If you do not wish to do so, delete this
exception statement from your version. */


package gnu.java.nio;


import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.nio.channels.spi.AbstractSelector;
import java.nio.channels.spi.SelectorProvider;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * A {@link Selector} implementation that uses the <code>kqueue</code>
 * event notification facility.
 *
 * @author Casey Marshall (csm@gnu.org)
 */
public class KqueueSelectorImpl extends AbstractSelector
{
  private static final int sizeof_struct_kevent;
  
  static
  {
    try
      {
        System.loadLibrary("javanio");
      }
    catch (Exception x)
      {
        x.printStackTrace();
      }

    if (kqueue_supported ())
      sizeof_struct_kevent = sizeof_struct_kevent();
    else
      sizeof_struct_kevent = -1;
  }
  
  /**
   * Tell if kqueue-based selectors are supported on this system.
   *
   * @return True if this system has kqueue support, and support for it was
   *  compiled in to Classpath.
   */
  public static native boolean kqueue_supported();
  
  /* Our native file descriptor. */
  private int kq;
  
  private HashMap/*<Integer,KqueueSelectionKeyImpl>*/ keys;
  private HashSet/*<KqueueSelectionKeyImpl>*/ selected;
  private HashSet/*<KqueueSelectionKeyImpl>*/ cancelled;
  private Thread blockedThread;

  public KqueueSelectorImpl(SelectorProvider provider) throws IOException
  {
    super(provider);
    kq = implOpen();
    keys = new HashMap/*<KqueueSelectionKeyImpl>*/();
    cancelled = new HashSet();
  }

  protected void implCloseSelector() throws IOException
  {
    implClose(kq);
    kq = -1;
  }

  /* (non-Javadoc)
   * @see java.nio.channels.Selector#keys()
   */
  public Set keys()
  {
    if (!isOpen())
      throw new ClosedSelectorException();

    return new HashSet(keys.values());
  }

  /* (non-Javadoc)
   * @see java.nio.channels.Selector#select()
   */
  public int select() throws IOException
  {
    return doSelect(-1);
  }

  /* (non-Javadoc)
   * @see java.nio.channels.Selector#select(long)
   */
  public int select(long timeout) throws IOException
  {
    if (timeout == 0)
      timeout = -1;
    return doSelect(timeout);
  }

  /* (non-Javadoc)
   * @see java.nio.channels.Selector#selectedKeys()
   */
  public Set selectedKeys()
  {
    if (!isOpen())
      throw new ClosedSelectorException();
    
    return selected;
  }

  /* (non-Javadoc)
   * @see java.nio.channels.Selector#selectNow()
   */
  public int selectNow() throws IOException
  {
    return doSelect(0);
  }

  /* (non-Javadoc)
   * @see java.nio.channels.Selector#wakeup()
   */
  public Selector wakeup()
  {
    if (blockedThread != null)
      blockedThread.interrupt();
    return this;
  }
  
  synchronized int doSelect(long timeout) throws IOException
  {
    // FIXME -- I'm unclear on how we should synchronize this; and how to
    // handle cancelled keys.
    for (Iterator it = cancelled.iterator(); it.hasNext(); )
      {
        KqueueSelectionKeyImpl key = (KqueueSelectionKeyImpl) it.next();
        updateOps(key, 0, true);
      }
    int events_size = 0;
    for (Iterator it = keys.values().iterator(); it.hasNext(); )
      {
        KqueueSelectionKeyImpl key = (KqueueSelectionKeyImpl) it.next();
        if ((key.interestOps & SelectionKey.OP_ACCEPT) != 0
            || (key.interestOps & SelectionKey.OP_READ) != 0)
          key.readEverEnabled = true;
        if ((key.interestOps & SelectionKey.OP_CONNECT) != 0
            || (key.interestOps & SelectionKey.OP_WRITE) != 0)
          key.writeEverEnabled = true;
        
        if (key.readEverEnabled)
          events_size += sizeof_struct_kevent;
        if (key.writeEverEnabled)
          events_size += sizeof_struct_kevent;
      }

    // We handle native events a little strangely here; per selection key,
    // we allocate enough space for two struct kevents, the first in the
    // list will be our EVFILT_READ filter, the second our EVFILT_WRITE
    // one. If only one of the two needs enabling, though, we don't want
    // to pass the other to kevent, because that would result in spurious
    // events. We can break down our handling as follows:
    //
    //   - READ enabled, WRITE never enabled. We pass only the first structure
    //     to kevent.
    //   - WRITE enabled, READ never enabled. Likewise, but only pass the
    //     second structure.
    //   - READ and WRITE enabled. Pass both.
    //   - READ enabled, WRITE enabled in the past. Pass both, with the
    //     first structure's flag set to EV_ADD or EV_ENABLE, and the second
    //     with EV_DISABLE. It seems OK to keep sending events with the
    //     EV_DISABLE flag.
    //   - WRITE enabled, READ enabled in the past. Likewise, but flipped.
    //
    // We handle these states with the readEverEnabled and writeEverEnabled
    // flags of selection keys; they start off as false, and become true
    // the first time we select() with READ or WRITE enabled. They never
    // become false.
    ByteBuffer events = ByteBuffer.allocateDirect(events_size);

    for (Iterator it = keys.entrySet().iterator(); it.hasNext(); )
      {
        Map.Entry e = (Map.Entry) it.next();
        KqueueSelectionKeyImpl key = (KqueueSelectionKeyImpl) e.getValue();
        
        if (key.readEverEnabled)
          events.put((ByteBuffer) key.nstate.duplicate().limit
                     (sizeof_struct_kevent));
        if (key.writeEverEnabled)
          events.put((ByteBuffer) key.nstate.duplicate().position
                     (sizeof_struct_kevent).limit(2 * sizeof_struct_kevent));
      }
    events.rewind();
    
    //System.out.println("dump of keys to select:");
    //dump_selection_keys(events.duplicate());
    
    blockedThread = Thread.currentThread();
    if (blockedThread.isInterrupted())
      timeout = 0;
    final int n = kevent(kq, events, events_size / sizeof_struct_kevent,
                         timeout);
    Thread.interrupted();
    
    //System.out.println("dump of keys selected:");
    //dump_selection_keys((ByteBuffer) events.duplicate().limit(n * sizeof_struct_kevent));

    selected = new HashSet/*<KqueueSelectionKeyImpl>*/(n);
    int x = 0;
    for (int i = 0; i < n; i++)
      {
        events.position(x).limit(x + sizeof_struct_kevent);
        x += sizeof_struct_kevent;
        int y = fetch_key(events.slice());
        KqueueSelectionKeyImpl key =
          (KqueueSelectionKeyImpl) keys.get(new Integer(y));
        key.readyOps = ready_ops(events.slice(), key.interestOps);
        selected.add(key);
      }
    for (Iterator it = cancelled.iterator(); it.hasNext(); )
      {
        KqueueSelectionKeyImpl key = (KqueueSelectionKeyImpl) it.next();
        keys.remove(new Integer(key.key));
        it.remove();
      }

    return selected.size();
  }
  
  protected SelectionKey register(AbstractSelectableChannel channel,
                                  int interestOps,
                                  Object attachment)
  {
    int native_fd = -1;
    try
      {
        if (channel instanceof VMChannelOwner)
          native_fd = ((VMChannelOwner) channel).getVMChannel()
            .getState().getNativeFD();
        else
          throw new IllegalArgumentException("cannot handle channel type " +
                                             channel.getClass().getName());
      }
    catch (IOException ioe)
      {
        throw new IllegalArgumentException("channel is closed or invalid");
      }
    
    KqueueSelectionKeyImpl result = new KqueueSelectionKeyImpl(this, channel);
    result.interestOps = interestOps;
    result.attach(attachment);
    int k = System.identityHashCode(result);
    while (keys.containsKey(new Integer(k)))
      k++;
    result.key = k;
    keys.put(new Integer(k), result);
    result.nstate = ByteBuffer.allocateDirect(2 * sizeof_struct_kevent);
    updateOps(result, native_fd, false);
    return result;
  }
  
  synchronized void updateOps(KqueueSelectionKeyImpl key)
  {
    updateOps(key, 0, false);
  }
  
  synchronized void updateOps(KqueueSelectionKeyImpl key, int fd, boolean delete)
  {
    //System.out.println(">> updating kqueue selection key:");
    //dump_selection_keys(key.nstate.duplicate());
    //System.out.println("<<");
    kevent_set(key.nstate, fd, key.interestOps, key.key, delete);
    //System.out.println(">> updated kqueue selection key:");
    //dump_selection_keys(key.nstate.duplicate());
    //System.out.println("<<");
  }
  
  synchronized void doCancel(KqueueSelectionKeyImpl key)
  {
    cancelled.add(key);
  }

  private void dump_selection_keys(ByteBuffer keys)
  {
    // WARNING! This method is not guaranteed to be portable! This works
    // on darwin/x86, but the sizeof and offsetof these fields may be
    // different on other platforms!
    int i = 0;
    keys.order(ByteOrder.nativeOrder());
    while (keys.hasRemaining())
      {
        System.out.println("struct kevent { ident: "
                           + Integer.toString(keys.getInt())
                           + " filter: "
                           + Integer.toHexString(keys.getShort() & 0xFFFF)
                           + " flags: "
                           + Integer.toHexString(keys.getShort() & 0xFFFF)
                           + " fflags: "
                           + Integer.toHexString(keys.getInt())
                           + " data: "
                           + Integer.toHexString(keys.getInt())
                           + " udata: "
                           + Integer.toHexString(keys.getInt())
                           + " }");
      }
  }
  
  /**
   * Return the size of a <code>struct kevent</code> on this system.
   * 
   * @return The size of <code>struct kevent</code>.
   */
  private static native int sizeof_struct_kevent();
  
  /**
   * Opens a kqueue descriptor.
   * 
   * @return The new kqueue descriptor.
   * @throws IOException If opening fails.
   */
  private static native int implOpen() throws IOException;
  
  /**
   * Closes the kqueue file descriptor.
   * 
   * @param kq The kqueue file descriptor.
   * @throws IOException
   */
  private static native void implClose(int kq) throws IOException;

  /**
   * Initialize the specified native state for the given interest ops.
   *
   * @param nstate The native state structures; in this buffer should be
   *  the <code>struct kevent</code>s created for a key.
   * @param fd The file descriptor. If 0, the native FD is unmodified.
   * @param interestOps The operations to enable.
   * @param key A unique key that will reference the associated key later.
   * @param delete Set to true if this event should be deleted from the
   *  kqueue (if false, this event is added/updated).
   */
  private static native void kevent_set(ByteBuffer nstate, int fd, int interestOps,
                                        int key, boolean delete);
  
  /**
   * Poll for events. The source events are stored in <code>events</code>,
   * which is also where polled events will be placed.
   *
   * @param events The events to poll. This buffer is also the destination
   *  for events read from the queue.
   * @param nevents The number of events to poll (that is, the number of
   *  events in the <code>events</code> buffer).
   * @param timeout The timeout. A timeout of -1 returns immediately; a timeout
   *  of 0 waits indefinitely.
   * @return The number of events read.
   */
  private static native int kevent(int kq, ByteBuffer events, int nevents,
                                   long timeout);
  
  /**
   * Fetch a polled key from a native state buffer. For each kevent key we
   * create, we put the native state info (one or more <code>struct
   *  kevent</code>s) in that key's {@link KqueueSelectionKeyImpl#nstate}
   * buffer, and place the pointer of the key in the <code>udata</code> field
   * of that structure. This method fetches that pointer from the given
   * buffer (assumed to be a <code>struct kqueue</code>) and returns it.
   *
   * @param nstate The buffer containing the <code>struct kqueue</code> to read.
   * @return The key object.
   */
  private static native int fetch_key(ByteBuffer nstate);
  
  /**
   * Fetch the ready ops of the associated native state. That is, this
   * inspects the first argument as a <code>struct kevent</code>, looking
   * at its operation (the input is assumed to have been returned via a
   * previous call to <code>kevent</code>), and translating that to the
   * appropriate Java bit set, based on the second argument.
   *
   * @param nstate The native state.
   * @param interestOps The enabled operations for the key.
   * @return The bit set representing the ready operations.
   */
  private static native int ready_ops(ByteBuffer nstate, int interestOps);
  
  /**
   * Check if kevent returned EV_EOF for a selection key.
   *
   * @param nstate The native state.
   * @return True if the kevent call returned EOF.
   */
  private static native boolean check_eof(ByteBuffer nstate);
}
