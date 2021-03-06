/*
 * Copyright (c) 2017 Sergey Chernov, iCodici S.n.C, All Rights Reserved
 *
 * Written by Sergey Chernov <real.sergeych@gmail.com>, August 2017.
 *
 */

package com.icodici.universa.node;

import com.icodici.universa.Approvable;
import com.icodici.universa.HashId;
import net.sergeych.utils.LogPrinter;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Local node is a {@link Node} implementation that performs voting and provides also client API interface.
 * <p>
 * Created by sergeych on 16/07/2017.
 */
public class LocalNode extends Node {

    static private LogPrinter log = new LogPrinter("LNOD");

    private final Network network;
    private final Ledger ledger;

    //    private final ConcurrentHashMap<HashId, Approvable> inputCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<HashId, Elections> allElections = new ConcurrentHashMap<>();
    /**
     * Mutex to check objects for external calls (e.g. network interfaces), to prevent race conditions when same item is
     * checked in the same time.
     */
    private Object checkLock = new Object();

    /**
     * Only for tests. Imitate download failure before consensus found.
     * <p>
     * DO NOT USE.
     */
    boolean lateDownload;

    public LocalNode(String id, Network network, Ledger ledger) {
        super(id);
        this.network = network;
        this.ledger = ledger;
    }

    public Network getNetwork() {
        return network;
    }

    // Node interface ---------------------------------------------------------------------------------------

    /**
     * Main entry point for {@link Approvable} item processing called by other nodes in the network. Nodes
     * <p>
     * <p>
     * If the item has solution in the system, what means that elections were performed and some consensus was found,
     * returns its result. If not, start new elections. See the {@link ItemResult}.
     *
     * @param caller   calling Node instance
     * @param itemId   item HashId
     * @param state    my state (could be ignored if the caller identity is not trusted)
     * @param haveCopy true if the caller has a copy of the item and can provide it with {@link #getItem(HashId)} call.
     *
     * @return current state of the item. Could be pending.
     *
     * @throws IOException
     */
    @Override
    public ItemResult checkItem(Node caller, HashId itemId, ItemState state, boolean haveCopy) throws IOException {
        // First, we can have it in the ledger
        ItemResult itemResult = processCheckItem(caller, itemId, state, haveCopy, null, null);
        log.d("" + this + " checkItem( from: " + caller + ":" + itemId + ":" + haveCopy + " << " + itemResult);
        return itemResult;
    }

    /**
     * Try our best to get the Approvable item. Note that successful elections do not necessarily means that the item
     * must be downloaded and therefore available from any node. Even, if the item was available when elections were
     * started, it could be wiped out at the end.
     *
     * @param itemId item to get
     *
     * @return item or null
     *
     * @throws IOException
     */
    @Override
    public Approvable getItem(HashId itemId) throws IOException {
        // The item could be either in a local cache or in the voters pool
        Elections elections = allElections.get(itemId);
        if (elections != null) {
            log.d("getItem(" + itemId + "): " + elections.getItem());
            return elections.getItem();
        }
        log.d("getItem(" + itemId + "): null");
        return null;
    }

    /**
     * Close all pending elections and free any allocated resources in it.
     */
    @Override
    public void shutdown() {
        allElections.forEach((id, e) -> e.close());
    }

    // Client API interface -----------------------------------------------------------------------------------

    /**
     * Try to register new item. If the item is already processed or being elected, it's current state is returned.
     *
     * @param item   to process
     * @param onDone consumer that will receive elections result
     *
     * @return the instance describing item's current state (at the time of calling)
     */
    public ItemInfo registerItem(Approvable item, Consumer<ItemResult> onDone) throws IOException {
        @NonNull ItemResult itemResult = processCheckItem(null, item.getId(), null, false, item, onDone);
        return new ItemInfo(itemResult, item);
    }

    /**
     * Shortcut to {@link #registerItem(Approvable, Consumer)} without onDone callback. Could be called several times
     * implementing polling item state waiting for consensus.
     *
     * @param item to register.
     *
     * @return current item state (at the time of calling, either some PENDING state or resovled one)
     *
     * @throws IOException
     */
    public ItemInfo registerItem(Approvable item) throws IOException {
        return registerItem(item, null);
    }

    /**
     * Check that the identified item is known to this node.
     * <p>
     * The problem. We might need to start elections to sync our ledger with the network. Or at least mark that we might
     * be out of sync with this item. We might try to ask some random nodes for it and fire elections only if at least
     * one is positive about it.
     *
     * @param itemId item to check
     *
     * @return result instance or null if it is not known to it.
     */
    public ItemResult checkItem(HashId itemId) {
        // It is cached if is elected, so we can just ask ledger
        StateRecord r = ledger.getRecord(itemId);
        return r == null ? null : new ItemResult(r);
    }

    // logic ------------------------------------------------------------------------------------------------------

    /**
     * Wait if need until the netowrk will find solution for the item. If it is not yet found, blocks until then. Do not
     * expose this method also indirectly to the clietn interface
     *
     * @param itemId
     *
     * @return
     *
     * @throws InterruptedException
     */
    public ItemResult waitForItem(HashId itemId) throws InterruptedException {
        Elections elections = allElections.get(itemId);
        if (elections == null) {
            StateRecord r = ledger.getRecord(itemId);
            return r == null ? null : new ItemResult(r);
        }
        elections.waitDone();
        return new ItemResult(elections.getRecord());
    }

    /**
     * For testing purposes only, do not expose in the client interface. May block until the elections are fininshed. If
     * the item already has the solution, returns it.
     *
     * @param item to register/check
     *
     * @return
     *
     * @throws Elections.Error
     * @throws InterruptedException
     */
    public ItemResult registerItemAndWait(Approvable item) throws Elections.Error, InterruptedException {
        processCheckItem(null, item.getId(), null, false, item, null);
        return waitForItem(item.getId());
    }

    /**
     * Process query if the item: the business-logic of the node. Check the consensus, if elections in progress,
     * register the voice of caller (if present), register caller as the source if need. If there us no solution in the
     * ledger and no elections in progress, starts new one.
     *
     * @param caller   calling node or null
     * @param itemId   id if the item in question.
     * @param state    it caller is not null, it could provide it's current state, if it already has local decision,
     *                 either ({@link ItemState#PENDING_POSITIVE} or {@link ItemState#PENDING_NEGATIVE}. Other states
     *                 are ignored and better aren't send.
     * @param haveCopy if caller is not null, true signals that it can provide it with {@link #getItem(HashId)}
     * @param item     optional, the copy of the item. Used when called from client API which often passes the item as a
     *                 parameter
     * @param onDone   optional consumer of the 'elections finished' event, either when consensus is found, timeout
     *                 expired or no quorum was found.
     *
     * @return result for the current state, useful if the state of the item was settled with consensus before
     *
     * @throws Elections.Error
     */
    @NonNull
    private ItemResult processCheckItem(Node caller, @NonNull HashId itemId, ItemState state, boolean haveCopy, Approvable item, Consumer<ItemResult> onDone) throws Elections.Error {
        // Check the election first, it is faster than checking teh ledger
        Elections elections = allElections.get(itemId);
        if (elections == null) {
            // It is not being elected, it could be in the ledger:
            StateRecord record = ledger.getRecord(itemId);
            if (record != null) {
                // We have state in ledger but already discarded the item itself
                ItemResult result = new ItemResult(record, false);
                if (onDone != null) {
                    onDone.accept(result);
                }
                return result;
            }
            // it is not in the ledger, it is not being elected, creeate new elections.
            // If it wil throw an exception, it would be processed by the caller

            // Race condition preventing - we should only create one elections for one itemId
            // but is should stay in allElections long enough so we will find it there anyway - if it even
            // was processed entirely while we were crawling to the point:
            synchronized (checkLock) {
                // we can go there by the time someone else just created elections for us:
                elections = allElections.get(itemId);
                if (elections == null) {
                    if (item != null) {
                        assert (item.getId().equals(itemId));
                        elections = new Elections(this, item);
                    } else
                        elections = new Elections(this, itemId);
                    allElections.put(itemId, elections);
                }
            }
            // from now we can and e should do everything in parallel again.
            // as starting elections includes long initial item checking procedure,
            // we do it outside of the checkLock mutex:
            elections.ensureStarted();

            // purge finished elections
            elections.onDone(itemResult -> {
                Elections.pool.schedule(() -> {
                    allElections.remove(itemId);
//                        log.i("elections+item purged: "+itemId);
                }, network.getMaxElectionsTime().toMillis(), TimeUnit.MILLISECONDS);
            });
        }
        if (caller != null && haveCopy)
            elections.addSourceNode(caller);
        if (caller != null && state != null) {
            switch (state) {
                case PENDING_POSITIVE:
                case APPROVED:
                    elections.registerVote(caller, true);
                    break;
                case PENDING_NEGATIVE:
                case REVOKED:
                case DECLINED:
                    elections.registerVote(caller, false);
                    break;
                default:
            }
        }
        if (onDone != null) {
            elections.onDone(onDone);
        }
        return new ItemResult(elections.getRecord(), elections.getItem() != null);
    }

    public Ledger getLedger() {
        return ledger;
    }

    @Override
    public String toString() {
        return "LN<" + getId() + ">";
    }

    /**
     * Testing only. Imitate situation when the item can't be downloaded prior to consensus found.
     */
    public void emulateLateDownload() {
        this.lateDownload = true;
    }
}
