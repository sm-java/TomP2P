package net.tomp2p.p2p;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableSet;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import net.tomp2p.Utils2;
import net.tomp2p.connection.Bindings;
import net.tomp2p.connection.ChannelCreator;
import net.tomp2p.connection.PeerConnection;
import net.tomp2p.futures.BaseFuture;
import net.tomp2p.futures.BaseFutureAdapter;
import net.tomp2p.futures.FutureBootstrap;
import net.tomp2p.futures.FutureChannelCreator;
import net.tomp2p.futures.FutureCreate;
import net.tomp2p.futures.FutureDHT;
import net.tomp2p.futures.FutureData;
import net.tomp2p.futures.FutureDiscover;
import net.tomp2p.futures.FutureLateJoin;
import net.tomp2p.futures.FutureResponse;
import net.tomp2p.p2p.DistributedHashTable.Operation;
import net.tomp2p.p2p.config.ConfigurationBaseDHT;
import net.tomp2p.p2p.config.ConfigurationGet;
import net.tomp2p.p2p.config.ConfigurationRemove;
import net.tomp2p.p2p.config.ConfigurationStore;
import net.tomp2p.p2p.config.Configurations;
import net.tomp2p.peers.Number160;
import net.tomp2p.peers.Number320;
import net.tomp2p.peers.Number480;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.peers.ShortString;
import net.tomp2p.rpc.HashData;
import net.tomp2p.rpc.ObjectDataReply;
import net.tomp2p.rpc.RawDataReply;
import net.tomp2p.rpc.StorageRPC;
import net.tomp2p.storage.Data;
import net.tomp2p.storage.StorageGeneric.ProtectionEnable;
import net.tomp2p.storage.StorageGeneric.ProtectionMode;
import net.tomp2p.storage.StorageMemory;
import net.tomp2p.utils.Timings;
import net.tomp2p.utils.Utils;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.junit.Assert;
import org.junit.Test;

public class TestDHT
{
	final private static Random rnd = new Random(42L);
	
	
	@Test
	public void testTooManyOpenFilesInSystem() throws Exception
	{
		Peer master = null;
		Peer slave = null;
		try
		{	
			master = new PeerMaker(new Number160(rnd)).setPorts(4001).buildAndListen();
			slave = new PeerMaker(new Number160(rnd)).setPorts(4002).buildAndListen();
			
			slave.setRawDataReply(new RawDataReply() 
			{	
				@Override
				public ChannelBuffer reply(PeerAddress sender, ChannelBuffer requestBuffer)
						throws Exception 
				{
					final byte[] b1=new byte[10000];
					int i=requestBuffer.getInt(0);
					ChannelBuffer ret=ChannelBuffers.wrappedBuffer(b1);
					ret.setInt(0, i);
					return ret;
				}
			});
			List<BaseFuture> list= new ArrayList<BaseFuture>();
			for(int i=0;i<200;i++)
			{
				final byte[] b=new byte[10000];
				PeerConnection pc=master.createPeerConnection(slave.getPeerAddress(), 5000);
				list.add(master.send(pc, ChannelBuffers.wrappedBuffer(b)));
				pc.close();
			}
			for(int i=0;i<20000;i++)
			{
				list.add(master.discover(slave.getPeerAddress()));
				final byte[] b=new byte[10000];
				byte[] me=Utils.intToByteArray(i);
				System.arraycopy(me, 0, b, 0, 4);
				list.add(master.send(slave.getPeerAddress(), ChannelBuffers.wrappedBuffer(b)));
				//Utils2.execute("netstat -tn | wc");
				//System.out.println(".");
			}
			for(BaseFuture bf:list)
			{
				bf.awaitUninterruptibly();
				if(bf.isFailed())
				{
					System.err.println("WTF "+bf.getFailedReason());
				}
				Assert.assertEquals(true, bf.isSuccess());
			}
			System.err.println("done!!");
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
		finally
		{
			System.err.println("done!1!");
			master.shutdown();
			slave.shutdown();
		}
	}
	
	@Test
	public void testBootstrapDiscover() throws Exception
	{
		Peer master = null;
		Peer slave = null;
		try
		{
			master = new PeerMaker(new Number160(rnd)).setPorts(4001).buildAndListen();
			slave = new PeerMaker(new Number160(rnd)).setPorts(4002).buildAndListen();
			FutureDiscover fd=master.discover(slave.getPeerAddress());
			fd.awaitUninterruptibly();
			System.err.println(fd.getFailedReason());
			Assert.assertEquals(true, fd.isSuccess());
		}
		finally
		{
			master.shutdown();
			slave.shutdown();
		}
	}
	
	@Test
	public void testBootstrapFail() throws Exception
	{
		Peer master = null;
		Peer slave = null;
		try
		{
			master = new PeerMaker(new Number160(rnd)).setPorts(4001).buildAndListen();
			slave = new PeerMaker(new Number160(rnd)).setPorts(4002).buildAndListen();
			FutureBootstrap fb=master.bootstrap(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 3000));
			fb.awaitUninterruptibly();
			Assert.assertEquals(false, fb.isSuccess());
			System.err.println(fb.getFailedReason());
			fb=master.bootstrap(slave.getPeerAddress());
			fb.awaitUninterruptibly();
			Assert.assertEquals(true, fb.isSuccess());
			
		}
		finally
		{
			master.shutdown();
			slave.shutdown();
		}
	}

	@Test
	public void testBootstrap() throws Exception
	{
		Peer master = null;
		try
		{
			// setup
			Peer[] peers = Utils2.createNodes(2000, rnd, 4001);
			master = peers[0];
			// do testing
			List<FutureBootstrap> tmp = new ArrayList<FutureBootstrap>();
			for (int i = 0; i < peers.length; i++)
			{
				if(peers[i]!=master)
				{
					FutureBootstrap res = peers[i].bootstrap(master.getPeerAddress());
					tmp.add(res);
				}
			}
			int i = 0;
			for (FutureBootstrap fm : tmp)
			{
				fm.awaitUninterruptibly();
				if (fm.isFailed())
					System.err.println(fm.getFailedReason());
				Assert.assertEquals(true, fm.isSuccess());
				System.err.println("i:" + (++i));
			}
		}
		finally
		{
			master.shutdown();
		}
	}

	@Test
	public void testBootstrap2() throws Exception
	{
		Peer master = null;
		try
		{
			// setup
			Peer[] peers = Utils2.createNodes(2000, rnd, 4001);
			master = peers[0];
			// do testing
			List<FutureBootstrap> tmp = new ArrayList<FutureBootstrap>();
			for (int i = 0; i < peers.length; i++)
			{
				if(peers[i] != master)
				{
					FutureBootstrap res = peers[i].bootstrap(master.getPeerAddress().createSocketTCP());
					tmp.add(res);
				}
			}
			int i = 0;
			for (FutureBootstrap fm : tmp)
			{
				fm.awaitUninterruptibly();
				if (fm.isFailed())
					System.err.println("FAILL:"+fm.getFailedReason());
				Assert.assertEquals(true, fm.isSuccess());
				System.err.println("i:" + (++i));
			}
		}
		finally
		{
			master.shutdown();
		}
	}

	@Test
	public void testBootstrap3() throws Exception
	{
		Peer master = null;
		try
		{
			// setup
			Peer[] peers = Utils2.createNodes(100, rnd, 4001);
			master = peers[0];
			// do testing
			List<FutureBootstrap> tmp = new ArrayList<FutureBootstrap>();
			// we start from 1, because a broadcast to ourself will not get replied.
			for (int i = 1; i < peers.length; i++)
			{
				FutureBootstrap res = peers[i].bootstrapBroadcast(4001);
				tmp.add(res);
			}
			int i = 0;
			for (FutureBootstrap fm : tmp)
			{
				System.err.println("i:" + (++i));
				fm.awaitUninterruptibly();
				if (fm.isFailed())
					System.err.println("error "+fm.getFailedReason());
				Assert.assertEquals(true, fm.isSuccess());
				
			}
		}
		finally
		{
			master.shutdown();
		}
	}

	@Test
	public void testBootstrap4() throws Exception
	{
		Peer master = null;
		Peer slave = null;
		try
		{
			master = new PeerMaker(new Number160(rnd)).setPorts(4001).buildAndListen();
			slave = new PeerMaker(new Number160(rnd)).setPorts(4002).buildAndListen();
			FutureLateJoin<FutureResponse> res = slave.pingBroadcast(4001);
			res.awaitUninterruptibly();
			Assert.assertEquals(true, res.isSuccess());
		}
		finally
		{
			master.shutdown();
			slave.shutdown();
		}
	}
	
	@Test
	public void testBootstrap5() throws Exception
	{
		Peer peer = null;
		try 
		{
			peer = new PeerMaker(new Number160(rnd)).setPorts(4000).buildAndListen();
			PeerAddress pa = new PeerAddress(new Number160(rnd), "192.168.77.77", 4000,4000);
			FutureBootstrap tmp = peer.bootstrap(pa);
			tmp.awaitUninterruptibly();
			Assert.assertEquals(false, tmp.isSuccess());
		}
		finally
		{
			peer.shutdown();
		}
	}
	
	/**
	 * This test works because if a peer bootstraps to itself, then its
	 * typically the bootstrapping peer
	 * 
	 * @throws Exception
	 */
	@Test
	public void testBootstrap6() throws Exception
	{
		Peer peer = null;
		try 
		{
			peer = new PeerMaker(new Number160(rnd)).setPorts(4000).buildAndListen();
			FutureBootstrap tmp = peer.bootstrap(peer.getPeerAddress());
			tmp.awaitUninterruptibly();
			Assert.assertEquals(true, tmp.isSuccess());
		}
		finally
		{
			peer.shutdown();
		}
	}
	
	/**
	 * This test fails because if a peer bootstraps to itself, then its
	 * typically the bootstrapping peer. However, if we bootstrap to more than
	 * one peer, and the boostrap fails, then we fail this test
	 * 
	 * @throws Exception
	 */
	@Test
	public void testBootstrap7() throws Exception
	{
		Peer peer = null;
		try 
		{
			peer = new PeerMaker(new Number160(rnd)).setPorts(4000).buildAndListen();
			
			Collection<PeerAddress> bootstrapTo = new ArrayList<PeerAddress>(2);
			PeerAddress pa = new PeerAddress(new Number160(rnd), "192.168.77.77", 4000,4000);
			bootstrapTo.add(peer.getPeerAddress());
			bootstrapTo.add(pa);
			FutureBootstrap tmp = peer.bootstrap(null, bootstrapTo, Configurations.defaultBootstrapConfiguration());
			tmp.awaitUninterruptibly();
			Assert.assertEquals(false, tmp.isSuccess());
		}
		finally
		{
			peer.shutdown();
		}
	}

	@Test
	public void testPut() throws Exception
	{
		Peer master = null;
		try
		{
			// setup
			Peer[] peers = Utils2.createNodes(2000, rnd, 4001);
			master = peers[0];
			Utils2.perfectRouting(peers);
			// do testing
			Data data = new Data(new byte[44444]);
			RoutingConfiguration rc = new RoutingConfiguration(2, 10, 2);
			RequestP2PConfiguration pc = new RequestP2PConfiguration(3, 5, 0);
			ConfigurationStore cs = Configurations.defaultStoreConfiguration();
			cs.setDomain(new ShortString("test").toNumber160());
			cs.setContentKey(new Number160(5));
			cs.setRequestP2PConfiguration(pc);
			cs.setRoutingConfiguration(rc);
			FutureDHT fdht = peers[444].put(peers[30].getPeerID(), data, cs);
			fdht.awaitUninterruptibly();
			fdht.getFutureRequests().awaitUninterruptibly();
			System.err.println("Test " + fdht.getFailedReason());
			Assert.assertEquals(true, fdht.isSuccess());
			// search top 3
			TreeMap<PeerAddress, Integer> tmp = new TreeMap<PeerAddress, Integer>(peers[30]
					.getPeerBean().getPeerMap().createPeerComparator(peers[30].getPeerID()));
			int i = 0;
			for (Peer node : peers)
			{
				tmp.put(node.getPeerAddress(), i);
				i++;
			}
			Entry<PeerAddress, Integer> e = tmp.pollFirstEntry();
			System.err.println("1 (" + e.getValue() + ")" + e.getKey());
			Assert.assertEquals(peers[e.getValue()].getPeerAddress(), peers[30].getPeerAddress());
			testForArray(peers[e.getValue()], peers[30].getPeerID(), true);
			//
			e = tmp.pollFirstEntry();
			System.err.println("2 (" + e.getValue() + ")" + e.getKey());
			testForArray(peers[e.getValue()], peers[30].getPeerID(), true);
			//
			e = tmp.pollFirstEntry();
			System.err.println("3 " + e.getKey());
			testForArray(peers[e.getValue()], peers[30].getPeerID(), true);
			//
			e = tmp.pollFirstEntry();
			System.err.println("4 " + e.getKey());
			testForArray(peers[e.getValue()], peers[30].getPeerID(), false);
		}
		finally
		{
			master.shutdown();
		}
	}

	@Test
	public void testPutGetAlone() throws Exception
	{
		Peer master = null;
		try
		{
			master = new PeerMaker(new Number160(rnd)).setPorts(4001).buildAndListen();
			FutureDHT fdht = master.put(Number160.ONE, new Data("hallo"));
			fdht.awaitUninterruptibly();
			fdht.getFutureRequests().awaitUninterruptibly();
			Assert.assertEquals(true, fdht.isSuccess());
			fdht = master.get(Number160.ONE);
			fdht.awaitUninterruptibly();
			Assert.assertEquals(true, fdht.isSuccess());
			Data tmp = fdht.getDataMap().get(Number160.ZERO);
			Assert.assertEquals("hallo", tmp.getObject().toString());
		}
		finally
		{
			master.shutdown();
		}
	}

	@Test
	public void testPut2() throws Exception
	{
		Peer master = null;
		try
		{
			// setup
			Peer[] peers = Utils2.createNodes(500, rnd, 4001);
			master = peers[0];
			Utils2.perfectRouting(peers);
			// do testing
			Data data = new Data(new byte[44444]);
			RoutingConfiguration rc = new RoutingConfiguration(0, 0, 1);
			RequestP2PConfiguration pc = new RequestP2PConfiguration(1, 0, 0);
			ConfigurationStore cs = Configurations.defaultStoreConfiguration();
			cs.setDomain(new ShortString("test").toNumber160());
			cs.setContentKey(new Number160(5));
			cs.setRequestP2PConfiguration(pc);
			cs.setRoutingConfiguration(rc);
			FutureDHT fdht = peers[444].put(peers[30].getPeerID(), data, cs);
			fdht.awaitUninterruptibly();
			fdht.getFutureRequests().awaitUninterruptibly();
			Assert.assertEquals(true, fdht.isSuccess());
			// search top 3
			TreeMap<PeerAddress, Integer> tmp = new TreeMap<PeerAddress, Integer>(peers[30]
					.getPeerBean().getPeerMap().createPeerComparator(peers[30].getPeerID()));
			int i = 0;
			for (Peer node : peers)
			{
				tmp.put(node.getPeerAddress(), i);
				i++;
			}
			Entry<PeerAddress, Integer> e = tmp.pollFirstEntry();
			Assert.assertEquals(peers[e.getValue()].getPeerAddress(), peers[30].getPeerAddress());
			testForArray(peers[e.getValue()], peers[30].getPeerID(), true);
			//
			e = tmp.pollFirstEntry();
			testForArray(peers[e.getValue()], peers[30].getPeerID(), false);
			//
			e = tmp.pollFirstEntry();
			testForArray(peers[e.getValue()], peers[30].getPeerID(), false);
			//
			e = tmp.pollFirstEntry();
			testForArray(peers[e.getValue()], peers[30].getPeerID(), false);
		}
		finally
		{
			master.shutdown();
		}
	}

	@Test
	public void testPutGet() throws Exception
	{
		Peer master = null;
		try
		{
			// setup
			Peer[] peers = Utils2.createNodes(2000, rnd, 4001);
			master = peers[0];
			Utils2.perfectRouting(peers);
			// do testing
			RoutingConfiguration rc = new RoutingConfiguration(2, 10, 2);
			RequestP2PConfiguration pc = new RequestP2PConfiguration(3, 5, 0);
			Data data = new Data(new byte[44444]);
			ConfigurationStore cs = Configurations.defaultStoreConfiguration();
			cs.setDomain(new ShortString("test").toNumber160());
			cs.setContentKey(new Number160(5));
			cs.setRequestP2PConfiguration(pc);
			cs.setRoutingConfiguration(rc);
			FutureDHT fdht = peers[444].put(peers[30].getPeerID(), data, cs);
			fdht.awaitUninterruptibly();
			fdht.getFutureRequests().awaitUninterruptibly();
			Assert.assertEquals(true, fdht.isSuccess());
			rc = new RoutingConfiguration(0, 0, 10, 1);
			pc = new RequestP2PConfiguration(1, 0, 0);
			ConfigurationGet cg = Configurations.defaultGetConfiguration();
			cg.setDomain(new ShortString("test").toNumber160());
			cg.setContentKey(new Number160(5));
			cg.setRequestP2PConfiguration(pc);
			cg.setRoutingConfiguration(rc);
			fdht = peers[555].get(peers[30].getPeerID(), cg);
			fdht.awaitUninterruptibly();
			Assert.assertEquals(true, fdht.isSuccess());
			Assert.assertEquals(1, fdht.getRawData().size());
			Assert.assertEquals(true, fdht.isMinReached());
		}
		finally
		{
			master.shutdown();
		}
	}

	@Test
	public void testPutGet2() throws Exception
	{
		Peer master = null;
		try
		{
			// setup
			Peer[] peers = Utils2.createNodes(1000, rnd, 4001);
			master = peers[0];
			Utils2.perfectRouting(peers);
			// do testing
			RoutingConfiguration rc = new RoutingConfiguration(2, 10, 2);
			RequestP2PConfiguration pc = new RequestP2PConfiguration(3, 5, 0);
			Data data = new Data(new byte[44444]);
			ConfigurationStore cs = Configurations.defaultStoreConfiguration();
			cs.setDomain(new ShortString("test").toNumber160());
			cs.setContentKey(new Number160(5));
			cs.setRequestP2PConfiguration(pc);
			cs.setRoutingConfiguration(rc);
			FutureDHT fdht = peers[444].put(peers[30].getPeerID(), data, cs);
			fdht.awaitUninterruptibly();
			fdht.getFutureRequests().awaitUninterruptibly();
			Assert.assertEquals(true, fdht.isSuccess());
			rc = new RoutingConfiguration(4, 0, 10, 1);
			pc = new RequestP2PConfiguration(4, 0, 0);
			ConfigurationGet cg = Configurations.defaultGetConfiguration();
			cg.setDomain(new ShortString("test").toNumber160());
			cg.setContentKey(new Number160(5));
			cg.setRequestP2PConfiguration(pc);
			cg.setRoutingConfiguration(rc);
			fdht = peers[555].get(peers[30].getPeerID(), cg);
			fdht.awaitUninterruptibly();
			Assert.assertEquals(true, fdht.isSuccess());
			Assert.assertEquals(1, fdht.getRawData().size());
			Assert.assertEquals(false, fdht.isMinReached());
		}
		finally
		{
			master.shutdown();
		}
	}

	@Test
	public void testPutGet3() throws Exception
	{
		Peer master = null;
		try
		{
			// setup
			Peer[] peers = Utils2.createNodes(2000, rnd, 4001);
			master = peers[0];
			Utils2.perfectRouting(peers);
			// do testing
			Data data = new Data(new byte[44444]);
			RoutingConfiguration rc = new RoutingConfiguration(2, 10, 2);
			RequestP2PConfiguration pc = new RequestP2PConfiguration(3, 5, 0);
			ConfigurationStore cs = Configurations.defaultStoreConfiguration();
			cs.setDomain(new ShortString("test").toNumber160());
			cs.setContentKey(new Number160(5));
			cs.setRequestP2PConfiguration(pc);
			cs.setRoutingConfiguration(rc);
			FutureDHT fdht = peers[444].put(peers[30].getPeerID(), data, cs);
			fdht.awaitUninterruptibly();
			fdht.getFutureRequests().awaitUninterruptibly();
			Assert.assertEquals(true, fdht.isSuccess());
			rc = new RoutingConfiguration(1, 0, 10, 1);
			pc = new RequestP2PConfiguration(1, 0, 0);
			ConfigurationGet cg = Configurations.defaultGetConfiguration();
			cg.setDomain(new ShortString("test").toNumber160());
			cg.setContentKey(new Number160(5));
			cg.setRequestP2PConfiguration(pc);
			cg.setRoutingConfiguration(rc);
			for (int i = 0; i < 1000; i++)
			{
				fdht = peers[100 + i].get(peers[30].getPeerID(), cg);
				fdht.awaitUninterruptibly();
				Assert.assertEquals(true, fdht.isSuccess());
				Assert.assertEquals(1, fdht.getRawData().size());
				Assert.assertEquals(true, fdht.isMinReached());
			}
		}
		finally
		{
			master.shutdown();
		}
	}

	@Test
	public void testPutGetRemove() throws Exception
	{
		Peer master = null;
		try
		{
			// setup
			Peer[] peers = Utils2.createNodes(2000, rnd, 4001);
			master = peers[0];
			Utils2.perfectRouting(peers);
			// do testing
			Data data = new Data(new byte[44444]);
			RoutingConfiguration rc = new RoutingConfiguration(2, 10, 2);
			RequestP2PConfiguration pc = new RequestP2PConfiguration(3, 5, 0);
			ConfigurationStore cs = Configurations.defaultStoreConfiguration();
			cs.setDomain(new ShortString("test").toNumber160());
			cs.setContentKey(new Number160(5));
			cs.setRequestP2PConfiguration(pc);
			cs.setRoutingConfiguration(rc);
			FutureDHT fdht = peers[444].put(peers[30].getPeerID(), data, cs);
			fdht.awaitUninterruptibly();
			fdht.getFutureRequests().awaitUninterruptibly();
			Assert.assertEquals(true, fdht.isSuccess());
			rc = new RoutingConfiguration(4, 0, 10, 1);
			pc = new RequestP2PConfiguration(4, 0, 0);
			ConfigurationRemove cr = Configurations.defaultRemoveConfiguration();
			cr.setDomain(new ShortString("test").toNumber160());
			cr.setContentKey(new Number160(5));
			cr.setRequestP2PConfiguration(pc);
			cr.setRoutingConfiguration(rc);
			fdht = peers[222].remove(peers[30].getPeerID(), cr);
			fdht.awaitUninterruptibly();
			Assert.assertEquals(true, fdht.isSuccess());
			Assert.assertEquals(3, fdht.getRawKeys().size());
			ConfigurationGet cg = Configurations.defaultGetConfiguration();
			cg.setDomain(new ShortString("test").toNumber160());
			cg.setContentKey(new Number160(5));
			cg.setRequestP2PConfiguration(pc);
			cg.setRoutingConfiguration(rc);
			fdht = peers[555].get(peers[30].getPeerID(), cg);
			fdht.awaitUninterruptibly();
			Assert.assertEquals(false, fdht.isSuccess());
		}
		finally
		{
			master.shutdown();
		}
	}

	@Test
	public void testPutGetRemove2() throws Exception
	{
		Peer master = null;
		try
		{
			//rnd.setSeed(253406013991563L);
			// setup
			Peer[] peers = Utils2.createNodes(2000, rnd, 4001);
			master = peers[0];
			Utils2.perfectRouting(peers);
			// do testing
			Data data = new Data(new byte[44444]);
			RoutingConfiguration rc = new RoutingConfiguration(2, 10, 2);
			RequestP2PConfiguration pc = new RequestP2PConfiguration(3, 5, 0);
			ConfigurationStore cs = Configurations.defaultStoreConfiguration();
			cs.setDomain(new ShortString("test").toNumber160());
			cs.setContentKey(new Number160(5));
			cs.setRequestP2PConfiguration(pc);
			cs.setRoutingConfiguration(rc);
			FutureDHT fdht = peers[444].put(peers[30].getPeerID(), data, cs);
			fdht.awaitUninterruptibly();
			fdht.getFutureRequests().awaitUninterruptibly();
			Assert.assertEquals(true, fdht.isSuccess());
			System.err.println("remove");
			rc = new RoutingConfiguration(4, 0, 10, 1);
			pc = new RequestP2PConfiguration(4, 0, 0);
			ConfigurationRemove cr = Configurations.defaultRemoveConfiguration();
			cr.setDomain(new ShortString("test").toNumber160());
			cr.setContentKey(new Number160(5));
			cr.setRequestP2PConfiguration(pc);
			cr.setRoutingConfiguration(rc);
			cr.setReturnResults(true);
			fdht = peers[222].remove(peers[30].getPeerID(), cr);
			fdht.awaitUninterruptibly();
			Assert.assertEquals(true, fdht.isSuccess());
			Assert.assertEquals(3, fdht.getRawData().size());
			System.err.println("get");
			rc = new RoutingConfiguration(4, 0, 0, 1);
			pc = new RequestP2PConfiguration(4, 0, 0);
			ConfigurationGet cg = Configurations.defaultGetConfiguration();
			cg.setDomain(new ShortString("test").toNumber160());
			cg.setContentKey(new Number160(5));
			cg.setRequestP2PConfiguration(pc);
			cg.setRoutingConfiguration(rc);
			fdht = peers[555].get(peers[30].getPeerID(), cg);
			fdht.awaitUninterruptibly();
			Assert.assertEquals(false, fdht.isSuccess());
		}
		finally
		{
			master.shutdown();
		}
	}

	@Test
	public void testDirect() throws Exception
	{
		Peer master = null;
		try
		{
			// setup
			Peer[] peers = Utils2.createNodes(1000, rnd, 4001);
			master = peers[0];
			Utils2.perfectRouting(peers);
			final AtomicInteger ai = new AtomicInteger(0);
			for (int i = 0; i < peers.length; i++)
			{
				peers[i].setObjectDataReply(new ObjectDataReply()
				{
					@Override
					public Object reply(PeerAddress sender, Object request) throws Exception
					{
						ai.incrementAndGet();
						return "ja";
					}
				});
			}
			// do testing
			FutureDHT f = peers[400].send(new Number160(rnd), "hallo");
			f.awaitUninterruptibly();
			System.err.println(f.getFailedReason());
			Assert.assertEquals(true, f.isSuccess());
			Assert.assertEquals(true, ai.get() >= 3 && ai.get() <= 6);
			Assert.assertEquals("ja", f.getObject());
		}
		finally
		{
			master.shutdown();
		}
	}
	
	@Test
	public void testDirect2() throws Exception
	{
		Peer sender = null;
		Peer recv1 = null;
		try
		{
			
			sender = new PeerMaker(new Number160("0x50")).setP2PId(55).setPorts(2424).setEnableMaintenance(false).buildAndListen();
			recv1 = new PeerMaker(new Number160("0x20")).setP2PId(55).setPorts(8088).setEnableMaintenance(false).buildAndListen();
			recv1.setObjectDataReply(new ObjectDataReply() {
				@Override
				public Object reply(PeerAddress sender, Object request) throws Exception {
					return "yes";
				}
			});
			PeerConnection peerConnection = sender.createPeerConnection(recv1.getPeerAddress(), 5*1000);
			FutureData fd1=sender.send(peerConnection, "test");
			fd1.awaitUninterruptibly();

			Assert.assertEquals(1.0d, sender.getPeerBean().getStatistics().getTCPChannelCount(),0.0d);
			Assert.assertEquals(1.0d, sender.getPeerBean().getStatistics().getTCPChannelCreationCount(),0.0d);
			System.out.println("#TCP=" +  sender.getPeerBean().getStatistics().getTCPChannelCount()+"/"+sender.getPeerBean().getStatistics().getTCPChannelCreationCount());
			Timings.sleep(7000);
			Assert.assertEquals(0.0d, sender.getPeerBean().getStatistics().getTCPChannelCount(),0.0d);
			Assert.assertEquals(1.0d, sender.getPeerBean().getStatistics().getTCPChannelCreationCount(),0.0d);
			System.out.println("#TCP=" +  sender.getPeerBean().getStatistics().getTCPChannelCount()+"/"+sender.getPeerBean().getStatistics().getTCPChannelCreationCount());
			FutureData fd2=sender.send(peerConnection, "test");
			fd2.awaitUninterruptibly();
			peerConnection.close();
			System.out.println("done");
			Thread.sleep(4000);
			System.out.println("#TCP=" +  sender.getPeerBean().getStatistics().getTCPChannelCount()+"/"+sender.getPeerBean().getStatistics().getTCPChannelCreationCount());
			Assert.assertEquals(1.0d, sender.getPeerBean().getStatistics().getTCPChannelCount(),0.0d);
			Assert.assertEquals(2.0d, sender.getPeerBean().getStatistics().getTCPChannelCreationCount(),0.0d);
		}
		finally
		{
			if (sender != null)
				sender.shutdown();
			if (recv1 != null)
				recv1.shutdown();
		}
	}

	@Test
	public void testAddGet() throws Exception
	{
		Peer master = null;
		try
		{
			// setup
			Peer[] peers = Utils2.createNodes(200, rnd, 4001);
			master = peers[0];
			Utils2.perfectRouting(peers);
			// do testing
			Number160 nr = new Number160(rnd);
			String toStore1 = "hallo1";
			String toStore2 = "hallo2";
			Data data1 = new Data(toStore1.getBytes());
			Data data2 = new Data(toStore2.getBytes());
			FutureDHT futureDHT = peers[30].add(nr, data1);
			futureDHT.awaitUninterruptibly();
			System.out.println("added: " + toStore1 + " (" + futureDHT.isSuccess() + ")");
			futureDHT = peers[50].add(nr, data2);
			futureDHT.awaitUninterruptibly();
			System.out.println("added: " + toStore2 + " (" + futureDHT.isSuccess() + ")");
			futureDHT = peers[77].getAll(nr);
			futureDHT.awaitUninterruptibly();
			Assert.assertEquals(true, futureDHT.isSuccess());
			Iterator<Data> iterator = futureDHT.getRawData().values().iterator().next().values()
					.iterator();
			System.out.println("got: " + new String(iterator.next().getData()) + " ("
					+ futureDHT.isSuccess() + ")");
			System.out.println("got: " + new String(iterator.next().getData()) + " ("
					+ futureDHT.isSuccess() + ")");
		}
		finally
		{
			master.shutdown();
		}
	}
	
	@Test
	public void testDigest() throws Exception
	{
		Peer master = null;
		try
		{
			// setup
			Peer[] peers = Utils2.createNodes(200, rnd, 4001);
			master = peers[0];
			Utils2.perfectRouting(peers);
			// do testing
			Number160 nr = new Number160(rnd);
			String toStore1 = "hallo1";
			String toStore2 = "hallo2";
			String toStore3 = "hallo3";
			Data data1 = new Data(toStore1.getBytes());
			Data data2 = new Data(toStore2.getBytes());
			Data data3 = new Data(toStore3.getBytes());
			FutureDHT futureDHT = peers[30].add(nr, data1);
			futureDHT.awaitUninterruptibly();
			System.out.println("added: " + toStore1 + " (" + futureDHT.isSuccess() + ")");
			futureDHT = peers[50].add(nr, data2);
			futureDHT.awaitUninterruptibly();
			System.out.println("added: " + toStore2 + " (" + futureDHT.isSuccess() + ")");
			futureDHT = peers[51].add(nr, data3);
			futureDHT.awaitUninterruptibly();
			System.out.println("added: " + toStore3 + " (" + futureDHT.isSuccess() + ")");
			futureDHT = peers[77].digestAll(nr);
			futureDHT.awaitUninterruptibly();
			System.err.println(futureDHT.getFailedReason());
			Assert.assertEquals(true, futureDHT.isSuccess());
			Assert.assertEquals(3, futureDHT.getDigest().getKeyDigest().size());
			Number160 test = new Number160("0x37bb570100c9f5445b534757ebc613a32df3836d");
			Set<Number160> test2 = new HashSet<Number160>();
			test2.add(test);
			futureDHT = peers[67].digest(nr, test2, Configurations.defaultGetConfiguration());
			futureDHT.awaitUninterruptibly();
			Assert.assertEquals(true, futureDHT.isSuccess());
			Assert.assertEquals(1, futureDHT.getDigest().getKeyDigest().size());
		}
		finally
		{
			master.shutdown();
		}
	}

	@Test
	public void testData() throws Exception
	{
		Peer master = null;
		try
		{
			// setup
			Peer[] peers = Utils2.createNodes(200, rnd, 4001);
			master = peers[0];
			Utils2.perfectRouting(peers);
			// do testing
			ChannelBuffer c = ChannelBuffers.dynamicBuffer();
			c.writeInt(77);
			peers[50].setRawDataReply(new RawDataReply()
			{
				@Override
				public ChannelBuffer reply(PeerAddress sender, ChannelBuffer requestBuffer)
				{
					System.err.println(requestBuffer.readInt());
					ChannelBuffer c = ChannelBuffers.dynamicBuffer();
					c.writeInt(88);
					return c;
				}
			});
			FutureData fd = master.send(peers[50].getPeerAddress(), c);
			fd.await();
			if (fd.getBuffer() == null)
				System.err.println("damm");
			int read = fd.getBuffer().readInt();
			Assert.assertEquals(88, read);
			System.err.println("done");
			//for(FutureBootstrap fb:tmp)
			//	fb.awaitUninterruptibly();
		}
		finally
		{
			master.shutdown();
		}
	}

	@Test
	public void testData2() throws Exception
	{
		Peer master = null;
		try
		{
			// setup
			Peer[] peers = Utils2.createNodes(200, rnd, 4001);
			master = peers[0];
			Utils2.perfectRouting(peers);
			// do testing
			ChannelBuffer c = ChannelBuffers.dynamicBuffer();
			c.writeInt(77);
			peers[50].setRawDataReply(new RawDataReply()
			{
				@Override
				public ChannelBuffer reply(PeerAddress sender, ChannelBuffer requestBuffer)
				{
					System.err.println(requestBuffer.readInt());
					return requestBuffer;
				}
			});
			FutureData fd = master.send(peers[50].getPeerAddress(), c);
			fd.await();
			System.err.println("done1");
			Assert.assertEquals(true, fd.isSuccess());
			Assert.assertNull(fd.getBuffer());
			// int read = fd.getBuffer().readInt();
			// Assert.assertEquals(88, read);
			System.err.println("done2");
		}
		finally
		{
			master.shutdown();
		}
	}

	@Test
	public void testObjectLoop() throws Exception
	{
		for (int i = 0; i < 1000; i++)
		{
			System.err.println("nr: "+i);
			testObject();
		}
	}

	@Test
	public void testObject() throws Exception
	{
		Peer master = null;
		try
		{
			// setup
			Peer[] peers = Utils2.createNodes(100, rnd, 4001);
			master = peers[0];
			Utils2.perfectRouting(peers);
			// do testing
			Number160 nr = new Number160(rnd);
			String toStore1 = "hallo1";
			String toStore2 = "hallo2";
			Data data1 = new Data(toStore1);
			Data data2 = new Data(toStore2);
			System.err.println("begin add : ");
			FutureDHT futureDHT = peers[30].add(nr, data1);
			futureDHT.awaitUninterruptibly();
			System.err.println("stop added: " + toStore1 + " (" + futureDHT.isSuccess() + ")");
			futureDHT = peers[50].add(nr, data2);
			futureDHT.awaitUninterruptibly();
			futureDHT.getFutureRequests().awaitUninterruptibly();
			System.err.println("added: " + toStore2 + " (" + futureDHT.isSuccess() + ")");
			futureDHT = peers[77].getAll(nr);
			futureDHT.awaitUninterruptibly();
			futureDHT.getFutureRequests().awaitUninterruptibly();
			if (!futureDHT.isSuccess())
				System.err.println(futureDHT.getFailedReason());
			Assert.assertEquals(true, futureDHT.isSuccess());
			
			Iterator<Data> iterator = futureDHT.getDataMap().values().iterator();
			//futureDHT.get
			Assert.assertEquals(2, futureDHT.getDataMap().size());
			System.err.println("got: " + iterator.next().getObject() + " ("
					+ futureDHT.isSuccess() + ")");
			System.err.println("got: " + iterator.next().getObject() + " ("
					+ futureDHT.isSuccess() + ")");
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
		finally
		{
			master.shutdown();
		}
	}

	@Test
	public void testAddGetPermits() throws Exception
	{
		Peer master = null;
		try
		{
			// setup
			Peer[] peers = Utils2.createNodes(2000, rnd, 4001);
			master = peers[0];
			Utils2.perfectRouting(peers);
			// do testing
			Number160 nr = new Number160(rnd);
			List<FutureDHT> list = new ArrayList<FutureDHT>();
			for (int i = 0; i < peers.length; i++)
			{
				String toStore1 = "hallo" + i;
				Data data1 = new Data(toStore1.getBytes());
				FutureDHT futureDHT = peers[i].add(nr, data1);
				list.add(futureDHT);
			}
			for (FutureDHT futureDHT : list)
			{
				futureDHT.awaitUninterruptibly();
				Assert.assertEquals(true, futureDHT.isSuccess());
			}
			System.err.println("DONE");
		}
		finally
		{
			master.shutdown();
		}
	}

	@Test
	public void testShutdown() throws Exception
	{
		Peer master1 = null;
		Peer master2 = null;
		Peer master3 = null;
		try
		{
			ConnectionConfiguration c = new ConnectionConfiguration();
			c.setIdleTCPMillis(Integer.MAX_VALUE);
			c.setIdleUDPMillis(Integer.MAX_VALUE);
			master1 = new PeerMaker(new Number160(rnd)).setP2PId(1).setConfiguration(c).setPorts(4001).buildAndListen();
			master2 = new PeerMaker(new Number160(rnd)).setP2PId(1).setConfiguration(c).setPorts(4002).buildAndListen();
			master3 = new PeerMaker(new Number160(rnd)).setP2PId(1).setConfiguration(c).setPorts(4003).buildAndListen();
			// perfect routing
			master1.getPeerBean().getPeerMap().peerFound(master2.getPeerAddress(), null);
			master1.getPeerBean().getPeerMap().peerFound(master3.getPeerAddress(), null);
			master2.getPeerBean().getPeerMap().peerFound(master1.getPeerAddress(), null);
			master2.getPeerBean().getPeerMap().peerFound(master3.getPeerAddress(), null);
			master3.getPeerBean().getPeerMap().peerFound(master1.getPeerAddress(), null);
			master3.getPeerBean().getPeerMap().peerFound(master2.getPeerAddress(), null);
			Number160 id = master2.getPeerID();
			Data data = new Data(new byte[44444]);
			RoutingConfiguration rc = new RoutingConfiguration(2, 10, 2);
			RequestP2PConfiguration pc = new RequestP2PConfiguration(3, 5, 0);
			ConfigurationStore cs = Configurations.defaultStoreConfiguration();
			cs.setDomain(new ShortString("test").toNumber160());
			cs.setContentKey(new Number160(5));
			cs.setRequestP2PConfiguration(pc);
			cs.setRoutingConfiguration(rc);
			FutureDHT fdht = master1.put(id, data, cs);
			fdht.awaitUninterruptibly();
			fdht.getFutureRequests().awaitUninterruptibly();
			Collection<Number160> tmp = new ArrayList<Number160>();
			tmp.add(new Number160(5));
			final FutureChannelCreator fcc=master1.getConnectionBean().getConnectionReservation().reserve(1);
			fcc.awaitUninterruptibly();
			ChannelCreator cc = fcc.getChannelCreator();
			
			FutureResponse fr = master1.getStoreRPC().get(master2.getPeerAddress(), id,
					new ShortString("test").toNumber160(), tmp, null, null, null, false, false, false, cc, false);
			fr.awaitUninterruptibly();
			Assert.assertEquals(1, fr.getResponse().getDataMap().size());
			fr = master1.getStoreRPC().get(master3.getPeerAddress(), id,
					new ShortString("test").toNumber160(), tmp, null, null, null, false, false, false, cc, false);
			fr.awaitUninterruptibly();
			Assert.assertEquals(1, fr.getResponse().getDataMap().size());
			fr = master1.getStoreRPC().get(master1.getPeerAddress(), id,
					new ShortString("test").toNumber160(), tmp, null, null, null, false, false, false, cc, false);
			fr.awaitUninterruptibly();
			Assert.assertEquals(1, fr.getResponse().getDataMap().size());
			//
			Assert.assertEquals(true, fdht.isSuccess());
			// search top 3
			master2.shutdown();
			master2 = new PeerMaker(new Number160(rnd)).setP2PId(1).setConfiguration(c).setPorts(4002).buildAndListen();
			//
			fr = master1.getStoreRPC().get(master2.getPeerAddress(), id,
					new ShortString("test").toNumber160(), tmp, null, null, null, false, false, false, cc, false);
			fr.awaitUninterruptibly();
			Assert.assertEquals(0, fr.getResponse().getDataMap().size());
			//
			ConfigurationGet cg = Configurations.defaultGetConfiguration();
			cg.setDomain(new ShortString("test").toNumber160());
			cg.setContentKey(new Number160(5));
			cg.setRequestP2PConfiguration(pc);
			cg.setRoutingConfiguration(rc);
			fdht = master1.get(id, cg);
			fdht.awaitUninterruptibly();
			Assert.assertEquals(true, fdht.isSuccess());
			System.err.println("no more exceptions here!!");
			fdht = master1.get(id, cg);
			fdht.awaitUninterruptibly();
			Assert.assertEquals(true, fdht.isSuccess());
			fdht = master1.get(id, cg);
			fdht.awaitUninterruptibly();
			Assert.assertEquals(true, fdht.isSuccess());
			master1.getConnectionBean().getConnectionReservation().release(cc);
		}
		finally
		{
			master1.shutdown();
			master2.shutdown();
			master3.shutdown();
		}
	}

	@Test
	public void testLogging() throws Exception
	{
		Peer master = null;
		try
		{
			master = new PeerMaker(new Number160(rnd)).setFileMessageLogger(new File("/tmp/p2plog.txt.gz")).buildAndListen();
			Peer[] peers = createNodes(master, 100);
			List<FutureBootstrap> tmp = new ArrayList<FutureBootstrap>();
			for (int i = 0; i < peers.length; i++)
			{
				FutureBootstrap res = peers[i].bootstrap(master.getPeerAddress().createSocketTCP());
				tmp.add(res);
			}
			int i = 0;
			for (FutureBootstrap fm : tmp)
			{
				fm.awaitUninterruptibly();
				if (fm.isFailed())
					System.err.println(fm.getFailedReason());
				Assert.assertEquals(true, fm.isSuccess());
				System.err.println("i:" + (++i));
			}
			Assert.assertEquals(true, new File("/tmp/p2plog.txt.gz").length() > 10000);
		}
		finally
		{
			master.shutdown();
		}
	}

	@Test
	public void testSecurePutGet1() throws Exception
	{
		Peer master = null;
		Peer slave1 = null;
		Peer slave2 = null;
		KeyPairGenerator gen = KeyPairGenerator.getInstance("DSA");
		KeyPair pair1 = gen.generateKeyPair();
		KeyPair pair2 = gen.generateKeyPair();
		KeyPair pair3 = gen.generateKeyPair();
		System.err.println("PPK1 " + pair1.getPublic());
		System.err.println("PPK2 " + pair2.getPublic());
		System.err.println("PPK3 " + pair3.getPublic());
		try
		{
			
			// make slave
			master = new PeerMaker(new Number160(rnd)).setKeyPair(pair1).setPorts(4001).buildAndListen();
			master.getPeerBean().getStorage().setProtection(ProtectionEnable.ALL,
					ProtectionMode.MASTER_PUBLIC_KEY, ProtectionEnable.ALL,
					ProtectionMode.MASTER_PUBLIC_KEY);
			slave1 = new PeerMaker(new Number160(rnd)).setKeyPair(pair2).setMasterPeer(master).buildAndListen();
			slave1.getPeerBean().getStorage().setProtection(ProtectionEnable.ALL,
					ProtectionMode.MASTER_PUBLIC_KEY, ProtectionEnable.ALL,
					ProtectionMode.MASTER_PUBLIC_KEY);
			slave2 = new PeerMaker(new Number160(rnd)).setKeyPair(pair3).setMasterPeer(master).buildAndListen();
			slave2.getPeerBean().getStorage().setProtection(ProtectionEnable.ALL,
					ProtectionMode.MASTER_PUBLIC_KEY, ProtectionEnable.ALL,
					ProtectionMode.MASTER_PUBLIC_KEY);
			// perfect routing
			master.getPeerBean().getPeerMap().peerFound(slave1.getPeerAddress(), null);
			master.getPeerBean().getPeerMap().peerFound(slave2.getPeerAddress(), null);
			//
			slave1.getPeerBean().getPeerMap().peerFound(master.getPeerAddress(), null);
			slave1.getPeerBean().getPeerMap().peerFound(slave2.getPeerAddress(), null);
			//
			slave2.getPeerBean().getPeerMap().peerFound(master.getPeerAddress(), null);
			slave2.getPeerBean().getPeerMap().peerFound(slave1.getPeerAddress(), null);
			Number160 locationKey = new Number160(50);
			ConfigurationStore cs1 = Configurations.defaultStoreConfiguration();
			Data data1 = new Data("test1");
			data1.setProtectedEntry(true);
			FutureDHT fdht1 = master.put(locationKey, data1, cs1);
			fdht1.awaitUninterruptibly();
			fdht1.getFutureRequests().awaitUninterruptibly();
			Assert.assertEquals(true, fdht1.isSuccess());
			// store again
			Data data2 = new Data("test1");
			data2.setProtectedEntry(true);
			FutureDHT fdht2 = slave1.put(locationKey, data2);
			fdht2.awaitUninterruptibly();
			fdht2.getFutureRequests().awaitUninterruptibly();
			Assert.assertEquals(0, fdht2.getKeys().size());
			Assert.assertEquals(false, fdht2.isSuccess());
			// Utils.sleep(1000000);
			// try to removze it
			FutureDHT fdht3 = slave2.remove(locationKey);
			fdht3.awaitUninterruptibly();
			// true, since we have domain protection yet
			Assert.assertEquals(true, fdht3.isSuccess());
			Assert.assertEquals(0, fdht3.getKeys().size());
			// try to put another thing
			cs1.setContentKey(new Number160(33));
			Data data3 = new Data("test2");
			data3.setProtectedEntry(true);
			FutureDHT fdht4 = master.put(locationKey, data3, cs1);
			fdht4.awaitUninterruptibly();
			fdht4.getFutureRequests().awaitUninterruptibly();
			Assert.assertEquals(true, fdht4.isSuccess());
			// get it
			FutureDHT fdht7 = slave2.getAll(locationKey);
			fdht7.awaitUninterruptibly();
			Assert.assertEquals(2, fdht7.getDataMap().size());
			Assert.assertEquals(true, fdht7.isSuccess());
			//if(true)
			//	System.exit(0);
			// try to remove for real, all
			ConfigurationRemove cr = Configurations.defaultRemoveConfiguration();
			cr.setSignMessage(true);
			FutureDHT fdht5 = master.removeAll(locationKey, cr);
			fdht5.awaitUninterruptibly();
			System.err.println(fdht5.getFailedReason());
			Assert.assertEquals(true, fdht5.isSuccess());
			// get all, they should be removed now
			FutureDHT fdht6 = slave2.getAll(locationKey);
			fdht6.awaitUninterruptibly();
			Assert.assertEquals(0, fdht6.getDataMap().size());
			Assert.assertEquals(false, fdht6.isSuccess());
			// put there the data again...
			cs1.setContentKey(Utils.makeSHAHash(pair1.getPublic().getEncoded()));
			FutureDHT fdht8 = slave1.put(locationKey, new Data("test1"), cs1);
			fdht8.awaitUninterruptibly();
			fdht8.getFutureRequests().awaitUninterruptibly();
			Assert.assertEquals(true, fdht8.isSuccess());
			// overwrite
			Data data4 = new Data("test1");
			data4.setProtectedEntry(true);
			FutureDHT fdht9 = master.put(locationKey, data4, cs1);
			fdht9.awaitUninterruptibly();
			fdht9.getFutureRequests().awaitUninterruptibly();
			System.err.println("reason " + fdht9.getFailedReason());
			Assert.assertEquals(true, fdht9.isSuccess());
		}
		finally
		{
			// Utils.sleep(1000000);
			master.shutdown();
			slave1.shutdown();
			slave2.shutdown();
		}
	}

	
	
	@Test
	public void testObjectSendExample() throws Exception
	{
		Peer p1 = null;
		Peer p2 = null;
		try
		{
			p1 = new PeerMaker(new Number160(rnd)).setPorts(4001).buildAndListen();
			p2 = new PeerMaker(new Number160(rnd)).setPorts(4002).buildAndListen();
			//attach reply handler
			p2.setObjectDataReply(new ObjectDataReply()
			{
				@Override
				public Object reply(PeerAddress sender, Object request) throws Exception
				{
					System.out.println("request ["+request+"]");
					return "world";
				}
			});
			FutureData futureData=p1.send(p2.getPeerAddress(), "hello");
			futureData.awaitUninterruptibly();
			System.out.println("reply ["+futureData.getObject()+"]");
		}
		finally
		{
			p1.shutdown();
			p2.shutdown();
		}
	}
	
	

	@Test
	public void testObjectSend() throws Exception
	{
		Peer master = null;
		try
		{
			// setup
			Peer[] peers = Utils2.createNodes(500, rnd, 4001);
			master = peers[0];
			Utils2.perfectRouting(peers);
			for (int i = 0; i < peers.length; i++)
			{
				System.err.println("node "+i);
				peers[i].setObjectDataReply(new ObjectDataReply()
				{
					@Override
					public Object reply(PeerAddress sender, Object request) throws Exception
					{
						return request;
					}
				});
				peers[i].setRawDataReply(new RawDataReply()
				{
					@Override
					public ChannelBuffer reply(PeerAddress sender, ChannelBuffer requestBuffer)
							throws Exception
					{
						return requestBuffer;
					}
				});
			}
			// do testing
			System.err.println("round start");
			Random rnd = new Random(42L);
			byte[] toStore1 = new byte[10 * 1024];
			for (int j = 0; j < 5; j++)
			{
				System.err.println("round "+j);
				for (int i = 0; i < peers.length - 1; i++)
				{
					send1(peers[rnd.nextInt(peers.length)], peers[rnd.nextInt(peers.length)],
							toStore1, 100);
					send2(peers[rnd.nextInt(peers.length)], peers[rnd.nextInt(peers.length)],
							ChannelBuffers.wrappedBuffer(toStore1), 100);
					System.err.println("round1 "+i);
				}
			}
			System.err.println("DONE");
		}
		catch(Exception e)
		{
			e.printStackTrace();
		}
		finally
		{
			master.shutdown();
		}
	}

	@Test
	public void testPassiveReplication() throws Exception
	{
		Peer master = null;
		try
		{
			// setup
			Peer[] peers = Utils2.createNodes(100, rnd, 4001);
			master = peers[0];
			List<FutureBootstrap> tmp = new ArrayList<FutureBootstrap>();
			for (int i = 0; i < peers.length; i++)
			{
				if(peers[i]!=master)
				{
					FutureBootstrap res = peers[i].bootstrap(master.getPeerAddress().createSocketTCP());
					tmp.add(res);
				}
			}
			int i = 0;
			for (FutureBootstrap fm : tmp)
			{
				fm.awaitUninterruptibly();
				if (fm.isFailed())
					System.err.println(fm.getFailedReason());
				Assert.assertEquals(true, fm.isSuccess());
				System.err.println("i:" + (++i));
			}
			final AtomicInteger counter = new AtomicInteger(0);
			final class MyStorageMemory extends StorageMemory
			{
				@Override
				public boolean put(Number160 locationKey, Number160 domainKey, Number160 contentKey, 
						Data newData, PublicKey publicKey, boolean putIfAbsent, boolean domainProtection)
				{
					System.err.println("here");
					counter.incrementAndGet();
					return super.put(locationKey, domainKey, contentKey , newData, publicKey, putIfAbsent, domainProtection);
				}
			}
			peers[50].getPeerBean().setStorage(new MyStorageMemory());
			ConfigurationStore cs = Configurations.defaultStoreConfiguration();
			cs.setRefreshSeconds(2);
			cs.setFutureCreate(new FutureCreate<FutureDHT>()
			{
				@Override
				public void repeated(FutureDHT future)
				{
					System.err.println("chain1...");
				}
			});
			FutureDHT fdht = peers[1].put(peers[50].getPeerID(), new Data("test"), cs);
			Timings.sleep(9 * 1000);
			Assert.assertEquals(5, counter.get());
			fdht.shutdown();
			System.err.println("stop chain1");
			ConfigurationRemove cr = Configurations.defaultRemoveConfiguration();
			cr.setRefreshSeconds(1);
			cr.setRepetitions(5);
			final AtomicInteger counter2 = new AtomicInteger(0);
			cr.setFutureCreate(new FutureCreate<FutureDHT>()
			{
				@Override
				public void repeated(FutureDHT future)
				{
					System.err.println("chain2...");
					counter2.incrementAndGet();
				}
			});
			FutureDHT fdht2 = peers[2].remove(peers[50].getPeerID(), cr);
			Timings.sleep(9 * 1000);
			Assert.assertEquals(5, counter.get());
			Assert.assertEquals(true, fdht2.isSuccess());
			Assert.assertEquals(5, counter2.get());
		}
		finally
		{
			master.shutdown();
		}
	}

	@Test
	public void testActiveReplicationForward() throws Exception
	{
		Random rnd = new Random(42L);
		Peer master = null;
		try
		{
			// setup
			Peer[] peers = Utils2.createNodes(100, rnd, 4001, 0, true);
			master = peers[0];
			Number160 locationKey = new Number160(rnd);
			// closest
			TreeSet<PeerAddress> tmp = new TreeSet<PeerAddress>(master.getPeerBean().getPeerMap()
					.createPeerComparator(locationKey));
			tmp.add(master.getPeerAddress());
			for (int i = 0; i < peers.length; i++)
				tmp.add(peers[i].getPeerAddress());
			PeerAddress closest = tmp.iterator().next();
			System.err.println("closest to " + locationKey + " is " + closest);
			// store
			Data data = new Data("Test");
			FutureDHT futureDHT = master.put(locationKey, data);
			futureDHT.awaitUninterruptibly();
			futureDHT.getFutureRequests().awaitUninterruptibly();
			Assert.assertEquals(true, futureDHT.isSuccess());
			List<FutureBootstrap> tmp2 = new ArrayList<FutureBootstrap>();
			for (int i = 0; i < peers.length; i++)
			{
				if(peers[i]!=master)
				{
					tmp2.add(peers[i].bootstrap(master.getPeerAddress().createSocketTCP()));
				}
			}
			for (FutureBootstrap fm : tmp2)
			{
				fm.awaitUninterruptibly();
				Assert.assertEquals(true, fm.isSuccess());
			}
			for (int i = 0; i < peers.length; i++)
			{
				for (BaseFuture baseFuture : peers[i].getPendingFutures().keySet())
					baseFuture.awaitUninterruptibly();
			}
			//wait for the replication
			Peer peerClose = searchPeer(closest, peers);
			int i=0;
			while(!peerClose.getPeerBean().getStorage().contains(locationKey, Configurations.DEFAULT_DOMAIN, Number160.ZERO))
			{
				Timings.sleep(250);
				i++;
				if(i>10)
					break;
			}
			final FutureChannelCreator fcc=master.getConnectionBean().getConnectionReservation().reserve(1);
			fcc.awaitUninterruptibly();
			ChannelCreator cc = fcc.getChannelCreator();
			FutureResponse futureResponse = peers[76].getStoreRPC().get(closest, locationKey,
					Configurations.DEFAULT_DOMAIN, null, null, null, null, false, false, false, cc, false);
			Utils.addReleaseListenerAll(futureResponse, master.getConnectionBean().getConnectionReservation(), cc);
			futureResponse.awaitUninterruptibly();
			Assert.assertEquals(true, futureResponse.isSuccess());
			Assert.assertEquals(1, futureResponse.getResponse().getDataMap().size());
			//master.getConnectionBean().getConnectionReservation().release(cc);
		}
		finally
		{
			master.shutdown();
		}
	}
	
	private Peer searchPeer(PeerAddress peerAddress, Peer[] peers)
	{
		for(Peer peer:peers)
		{
			if(peer.getPeerAddress().equals(peerAddress))
				return peer;
		}
		return null;
	}

	@Test
	public void testActiveReplicationRefresh() throws Exception
	{
		Peer master = null;
		try
		{
			// setup
			Peer[] peers = Utils2.createNodes(100, rnd, 4001, 5 * 1000, true);
			master = peers[0];
			Number160 locationKey = master.getPeerID().xor(new Number160(77));
			// store
			Data data = new Data("Test");
			FutureDHT futureDHT = master.put(locationKey, data);
			futureDHT.awaitUninterruptibly();
			futureDHT.getFutureRequests().awaitUninterruptibly();
			Assert.assertEquals(true, futureDHT.isSuccess());
			// bootstrap
			List<FutureBootstrap> tmp2 = new ArrayList<FutureBootstrap>();
			for (int i = 0; i < peers.length; i++)
			{
				if(peers[i]!=master)
				{
					tmp2.add(peers[i].bootstrap(master.getPeerAddress().createSocketTCP()));
				}
			}
			for (FutureBootstrap fm : tmp2)
			{
				fm.awaitUninterruptibly();
				Assert.assertEquals(true, fm.isSuccess());
			}
			for (int i = 0; i < peers.length; i++)
			{
				for (BaseFuture baseFuture : peers[i].getPendingFutures().keySet())
					baseFuture.awaitUninterruptibly();
			}
			// wait for refresh
			Thread.sleep(6000);
			//
			TreeSet<PeerAddress> tmp = new TreeSet<PeerAddress>(master.getPeerBean().getPeerMap()
					.createPeerComparator(locationKey));
			tmp.add(master.getPeerAddress());
			for (int i = 0; i < peers.length; i++)
				tmp.add(peers[i].getPeerAddress());
			int i = 0;
			for (PeerAddress closest : tmp)
			{
				final FutureChannelCreator fcc=master.getConnectionBean().getConnectionReservation().reserve(1);
				fcc.awaitUninterruptibly();
				ChannelCreator cc = fcc.getChannelCreator();
				FutureResponse futureResponse = master.getStoreRPC().get(closest, locationKey,
						Configurations.DEFAULT_DOMAIN, null, null, null, null, false, false, false, cc, false);
				futureResponse.awaitUninterruptibly();
				master.getConnectionBean().getConnectionReservation().release(cc);
				Assert.assertEquals(true, futureResponse.isSuccess());
				Assert.assertEquals(1, futureResponse.getResponse().getDataMap().size());
				i++;
				if (i >= 5)
					break;
			}
		}
		finally
		{
			master.shutdown();
		}
	}
	
	@Test
	public void testKeys() throws Exception
	{
		final Random rnd = new Random(42L);
		Peer p1 = null;
		Peer p2 = null;
		try
		{
			Number160 n1=new Number160(rnd);
			Data d1=new Data("hello");
			Data d2=new Data("world!");
			// setup (step 1)
			p1 = new PeerMaker(new Number160(rnd)).setPorts(4001).buildAndListen();
			FutureDHT futureDHT = p1.add(n1, d1);
			futureDHT.awaitUninterruptibly();
			Assert.assertEquals(true, futureDHT.isSuccess());
			p2 = new PeerMaker(new Number160(rnd)).setPorts(4002).buildAndListen();
			p2.bootstrap(p1.getPeerAddress()).awaitUninterruptibly();
			// test (step 2)
			futureDHT = p1.add(n1, d2);
			futureDHT.awaitUninterruptibly();
			Assert.assertEquals(true, futureDHT.isSuccess());
			futureDHT = p2.getAll(n1);
			futureDHT.awaitUninterruptibly();
			Assert.assertEquals(2, futureDHT.getDataMap().size());
			// test (step 3)
			futureDHT = p1.remove(n1, d2.getHash());
			futureDHT.awaitUninterruptibly();
			Assert.assertEquals(true, futureDHT.isSuccess());
			futureDHT = p2.getAll(n1);
			futureDHT.awaitUninterruptibly();
			Assert.assertEquals(1, futureDHT.getDataMap().size());
			// test (step 4)
			futureDHT = p1.add(n1, d2);
			futureDHT.awaitUninterruptibly();
			Assert.assertEquals(true, futureDHT.isSuccess());
			futureDHT = p2.getAll(n1);
			futureDHT.awaitUninterruptibly();
			Assert.assertEquals(2, futureDHT.getDataMap().size());
			// test (remove all)
			futureDHT = p1.remove(n1, d1.getHash());
			futureDHT.awaitUninterruptibly();
			futureDHT = p1.remove(n1, d2.getHash());
			futureDHT.awaitUninterruptibly();
			futureDHT = p2.getAll(n1);
			futureDHT.awaitUninterruptibly();
			Assert.assertEquals(0, futureDHT.getDataMap().size());
		}
		finally
		{
			p1.shutdown();
			p2.shutdown();
		}
	}
	
	@Test
	public void testKeys2() throws Exception
	{
		final Random rnd = new Random(42L);
		Peer p1 = null;
		Peer p2 = null;
		try
		{
			Number160 n1=new Number160(rnd);
			Number160 n2=new Number160(rnd);
			Data d1=new Data("hello");
			Data d2=new Data("world!");
			// setup (step 1)
			p1 = new PeerMaker(new Number160(rnd)).setPorts(4001).buildAndListen();
			FutureDHT futureDHT = p1.put(n1, d1);
			futureDHT.awaitUninterruptibly();
			Assert.assertEquals(true, futureDHT.isSuccess());
			p2 = new PeerMaker(new Number160(rnd)).setPorts(4002).buildAndListen();
			p2.bootstrap(p1.getPeerAddress()).awaitUninterruptibly();
			// test (step 2)
			futureDHT = p1.put(n2, d2);
			futureDHT.awaitUninterruptibly();
			Assert.assertEquals(true, futureDHT.isSuccess());
			futureDHT = p2.get(n2);
			futureDHT.awaitUninterruptibly();
			Assert.assertEquals(1, futureDHT.getDataMap().size());
			// test (step 3)
			futureDHT = p1.remove(n2);
			futureDHT.awaitUninterruptibly();
			Assert.assertEquals(true, futureDHT.isSuccess());
			futureDHT = p2.get(n2);
			futureDHT.awaitUninterruptibly();
			Assert.assertEquals(0, futureDHT.getDataMap().size());
			// test (step 4)
			futureDHT = p1.put(n2, d2);
			futureDHT.awaitUninterruptibly();
			Assert.assertEquals(true, futureDHT.isSuccess());
			futureDHT = p2.get(n2);
			futureDHT.awaitUninterruptibly();
			Assert.assertEquals(1, futureDHT.getDataMap().size());
		}
		finally
		{
			p1.shutdown();
			p2.shutdown();
		}
	}
	
	@Test
	public void testPutGetAll() throws Exception
	{
		final AtomicBoolean running = new AtomicBoolean(true);
		Peer master = null;
		try
		{
			// setup
			final Peer[] peers = Utils2.createNodes(100, rnd, 4001);
			master = peers[0];
			Utils2.perfectRouting(peers);
			final Number160 key= Number160.createHash("test"); 
			final Data data1 = new Data("test1");
			data1.setTTLSeconds(3);
			final Data data2 = new Data("test2");
			data2.setTTLSeconds(3);
			
			// add every second a two values
			Thread t = new Thread(new Runnable()
			{
				@Override
				public void run()
				{
					while(running.get())
					{
						peers[10].add(key, data1).awaitUninterruptibly();
						peers[10].add(key, data2).awaitUninterruptibly();
						Timings.sleepUninterruptibly(1000);
					}
				}
			});
			t.start();
			//wait until the first data is stored.
			Timings.sleep(1000);
			for(int i=0;i<30;i++)
			{
				FutureDHT futureDHT = peers[20+i].getAll(key);
				futureDHT.awaitUninterruptibly();
				Assert.assertEquals(2, futureDHT.getDataMap().size());
				Timings.sleep(1000);
			}
		}
		finally
		{
			running.set(false);
			master.shutdown();
		}
	}
	
	/**
	 * This will probably fail on your machine since you have to have eth0 configured. This testcase is suited
	 * for running on the tomp2p.net server
	 * @throws Exception
	 */
	@Test
	public void testBindings() throws Exception
	{
		final Random rnd = new Random(42L);
			Peer p1 = null;
			Peer p2 = null;
			try
			{
				// setup (step 1)
				Bindings b = new Bindings();
				b.addInterface("eth0");
				p1 = new PeerMaker(new Number160(rnd)).setPorts(4001).setBindings(b).buildAndListen();
				p2 = new PeerMaker(new Number160(rnd)).setPorts(4002).setBindings(b).buildAndListen();
				FutureBootstrap fb=p2.bootstrap(p1.getPeerAddress());
				fb.awaitUninterruptibly();
				Assert.assertEquals(true, fb.isSuccess());
			}
			finally
			{
				p1.shutdown();
				p2.shutdown();
			}
	}
	
	@Test
	public void testCompareAndPut() throws Exception
	{
		final AtomicBoolean running = new AtomicBoolean(true);
		Peer master = null;
		try
		{
			// setup
			final Peer[] peers = Utils2.createNodes(100, rnd, 4001);
			master = peers[0];
			final StorageRPC storageRPC = master.getStoreRPC();
			Utils2.perfectRouting(peers);
			final Number160 locationKey = Number160.createHash("1");
			final Number160 domainKey = Number160.createHash("2");
			final Data testDataOld = new Data("test old");
			final Data testDataOld2 = new Data("test old2");
			final Data testDataNew = new Data("test new");
			final NavigableSet<PeerAddress> queue = new TreeSet<PeerAddress>();
			queue.add(peers[1].getPeerAddress()); 
			queue.add(peers[2].getPeerAddress());
			queue.add(peers[3].getPeerAddress());
			RequestP2PConfiguration p2pConfiguration = new RequestP2PConfiguration(3, Integer.MAX_VALUE, 0);
			
			setData(peers[1], "1", "2", "3", testDataOld);
			setData(peers[2], "1", "2", "3", testDataOld);
			// wrong hash here
			setData(peers[3], "1", "2", "3", testDataOld2);
			
			ConfigurationBaseDHT config = new ConfigurationBaseDHT();
			config.setRequestP2PConfiguration(p2pConfiguration);
			config.setAutomaticCleanup(true);
			config.setDomain(domainKey);
			FutureDHT futureDHT = master.parallelRequests(locationKey, config, false, queue, new Operation()
			{
				@Override
				public void response(FutureDHT futureDHT)
				{
					System.out.println("done! "+futureDHT);
					futureDHT.setDone("not failed");
				}
				
				@Override
				public void interMediateResponse(FutureResponse futureResponse)
				{
					System.out.println("progres! "+futureResponse);
					if(futureResponse.isFailed())
					{
						//go again...
						queue.add(futureResponse.getRequest().getRecipient());
					}
				}
				
				@Override
				public FutureResponse create(ChannelCreator channelCreator, PeerAddress address)
				{
					Map<Number160, HashData> hashDataMap = new HashMap<Number160, HashData>();
					hashDataMap.put(Number160.createHash("3"), new HashData(testDataOld.getHash(), testDataNew));
					return storageRPC.compareAndPut(address, locationKey, domainKey, hashDataMap, 
							false, false, false, false, channelCreator, false);
				}
			});
			Timings.sleepUninterruptibly(1300);
			Assert.assertEquals(false, futureDHT.isCompleted());
			//update peer with old data
			setData(peers[3], "1", "2", "3", testDataOld);
			futureDHT.awaitUninterruptibly();
			Assert.assertEquals(true, futureDHT.isCompleted());
			Assert.assertEquals("not failed", futureDHT.getAttachement());
		}
		finally
		{
			running.set(false);
			master.shutdown();
		}
	}
	
	private void setData(Peer peer, String location, String domain, String content, Data data) throws IOException
	{
		final Number160 locationKey = Number160.createHash(location);
		final Number160 domainKey = Number160.createHash(domain);
		final Number160 contentKey = Number160.createHash(content);
		peer.getPeerBean().getStorage().put(locationKey, domainKey, contentKey, data, null, false, false);
	}

	private void send2(final Peer p1, final Peer p2, final ChannelBuffer toStore1, final int count)
			throws IOException
	{
		if (count == 0)
		{
			System.err.println("failed miserably");
			return;
		}
		FutureData fd = p1.send(p2.getPeerAddress(), toStore1);
		fd.addListener(new BaseFutureAdapter<FutureData>()
		{
			@Override
			public void operationComplete(FutureData future) throws Exception
			{
				if (future.isFailed())
				{
					// System.err.println(future.getFailedReason());
					send2(p1, p2, toStore1, count - 1);
				}
			}
		});
	}

	private void send1(final Peer p1, final Peer p2, final byte[] toStore1, final int count)
			throws IOException
	{
		if (count == 0)
		{
			System.err.println("failed miserably");
			return;
		}
		FutureData fd = p1.send(p2.getPeerAddress(), toStore1);
		fd.addListener(new BaseFutureAdapter<FutureData>()
		{
			@Override
			public void operationComplete(FutureData future) throws Exception
			{
				if (future.isFailed())
				{
					// System.err.println(future.getFailedReason());
					send1(p1, p2, toStore1, count - 1);
				}
			}
		});
	}

	private void testForArray(Peer peer, Number160 locationKey, boolean find)
	{
		Collection<Number160> tmp = new ArrayList<Number160>();
		tmp.add(new Number160(5));
		Map<Number480, Data> test =peer.getPeerBean().getStorage().subMap(locationKey, new ShortString("test").toNumber160(), Number160.ZERO, Number160.MAX_VALUE);
		if (find)
		{
			Assert.assertEquals(1, test.size());
			Assert.assertEquals(44444, test.get(new Number480(new Number320(locationKey, new ShortString("test").toNumber160()), new Number160(5))).getLength());
		}
		else
			Assert.assertEquals(0, test.size());
	}

	private Peer[] createNodes(Peer master, int nr, Random rnd) throws Exception
	{
		Peer[] peers = new Peer[nr];
		for (int i = 0; i < nr; i++)
		{
			peers[i] = new PeerMaker(new Number160(rnd)).setP2PId(1).setMasterPeer(master).buildAndListen();
		}
		return peers;
	}

	private Peer[] createNodes(Peer master, int nr) throws Exception
	{
		return createNodes(master, nr, rnd);
	}
	
	
}
