package network.thunder.core.communication.objects.messages.impl.blockchainlistener;

import network.thunder.core.communication.objects.messages.impl.blockchainlistener.bciapi.BlockExplorer;
import network.thunder.core.communication.objects.messages.interfaces.helper.BlockchainHelper;
import network.thunder.core.communication.objects.messages.interfaces.helper.etc.OnBlockCommand;
import network.thunder.core.communication.objects.messages.interfaces.helper.etc.OnTxCommand;
import network.thunder.core.etc.Constants;
import org.bitcoinj.core.*;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.MemoryBlockStore;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * BlockchainHelper based upon connecting directly to p2p network with bitcoinJ.
 * Using BC.i for getTransaction, as this functionality is not generally accessible normally.
 * <p>
 * Would be great to have another implementation in the future that only connects to a local bitcoind,
 * to greatly improve privacy and reduce dependency on third parties.
 */
public class BlockchainHelperImpl implements BlockchainHelper {

    PeerGroup peerGroup;
    BlockStore blockStore;
    BlockChain blockChain;

    Set<Sha256Hash> processedMessages = new HashSet<>();

    ThreadPoolExecutor poolExecutor = new ThreadPoolExecutor(8, 8, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<>());

    List<OnBlockCommand> blockListener = Collections.synchronizedList(new ArrayList<>());
    List<OnTxCommand> txListener = Collections.synchronizedList(new ArrayList<>());

    BlockExplorer blockExplorer = new BlockExplorer();

    Boolean initialized;

    @Override
    public boolean broadcastTransaction (Transaction tx) {
        try {
            TransactionBroadcast broadcast = peerGroup.broadcastTransaction(tx);
            broadcast.future().get(10, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public void addTxListener (OnTxCommand executor) {
        txListener.add(executor);
    }

    @Override
    public void addBlockListener (OnBlockCommand executor) {
        blockListener.add(executor);
    }

    @Override
    public Transaction getTransaction (Sha256Hash hash) {
        try {
            Transaction transaction = blockExplorer.getBitcoinJTransaction(hash.toString());
            //TODO maybe get the number of confirmations in here somehow
            return transaction;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void init () {
        synchronized (initialized) {
            if (!initialized) {
                try {
                    blockStore = new MemoryBlockStore(Constants.getNetwork());
                    blockChain = new BlockChain(Constants.getNetwork(), blockStore);
                    peerGroup = new PeerGroup(Constants.getNetwork(), blockChain);
                    peerGroup.addPeerDiscovery(new DnsDiscovery(Constants.getNetwork()));
                    peerGroup.setDownloadTxDependencies(false);
                    peerGroup.setBloomFilteringEnabled(false);
                    peerGroup.setFastCatchupTimeSecs(System.currentTimeMillis());
                    peerGroup.start();

                    System.out.println("Download BlockHeaders..");
                    final DownloadProgressTracker listener = new DownloadProgressTracker();
                    peerGroup.startBlockChainDownload(listener);
                    listener.await();

                    peerGroup.addEventListener(new EventListener());

                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                initialized = true;
            }
        }
    }

    class EventListener implements PeerEventListener {

        @Override
        public void onPeersDiscovered (Set<PeerAddress> peerAddresses) {

        }

        @Override
        public void onBlocksDownloaded (Peer peer, Block block, @Nullable FilteredBlock filteredBlock, int blocksLeft) {

        }

        @Override
        public void onChainDownloadStarted (Peer peer, int blocksLeft) {

        }

        @Override
        public void onPeerConnected (Peer peer, int peerCount) {

        }

        @Override
        public void onPeerDisconnected (Peer peer, int peerCount) {

        }

        @Override
        public Message onPreMessageReceived (Peer peer, Message m) {
            if (m instanceof Block || m instanceof Transaction) {
                processedMessages.add(m.getHash());
                poolExecutor.submit((Runnable) () -> {
                    if (m instanceof Block) {
                        Iterator<OnBlockCommand> iterator = blockListener.iterator();
                        while (iterator.hasNext()) {
                            OnBlockCommand onBlockCommand = iterator.next();
                            if (onBlockCommand.execute((Block) m)) {
                                iterator.remove();
                            }
                        }
                    } else {
                        Transaction transaction = (Transaction) m;
                        Iterator<OnTxCommand> iterator = txListener.iterator();
                        while (iterator.hasNext()) {
                            OnTxCommand onTxCommand = iterator.next();
                            if (onTxCommand.compare(transaction)) {
                                onTxCommand.execute(transaction);
                                iterator.remove();
                            }
                        }
                    }
                });
            }
            return m;
        }

        @Override
        public void onTransaction (Peer peer, Transaction t) {

        }

        @Nullable
        @Override
        public List<Message> getData (Peer peer, GetDataMessage m) {
            return null;
        }
    }
}