/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.util;

import org.junit.Assert;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Set;

import static org.hamcrest.core.IsCollectionContaining.hasItems;
import static org.hamcrest.core.IsNot.not;
import static org.junit.Assert.*;

public class NetUtilsTest {

	@Test
	public void testIPv4toURL() {
		try {
			final String addressString = "192.168.0.1";

			InetAddress address = InetAddress.getByName(addressString);
			assertEquals(addressString, NetUtils.ipAddressToUrlString(address));
		}
		catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
	}

	@Test
	public void testIPv6toURL() {
		try {
			final String addressString = "2001:01db8:00:0:00:ff00:42:8329";
			final String normalizedAddress = "[2001:1db8::ff00:42:8329]";

			InetAddress address = InetAddress.getByName(addressString);
			assertEquals(normalizedAddress, NetUtils.ipAddressToUrlString(address));
		}
		catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
	}
	
	@Test
	public void testIPv4URLEncoding() {
		try {
			final String addressString = "10.244.243.12";
			final int port = 23453;
			
			InetAddress address = InetAddress.getByName(addressString);
			InetSocketAddress socketAddress = new InetSocketAddress(address, port);
			
			assertEquals(addressString, NetUtils.ipAddressToUrlString(address));
			assertEquals(addressString + ':' + port, NetUtils.ipAddressAndPortToUrlString(address, port));
			assertEquals(addressString + ':' + port, NetUtils.socketAddressToUrlString(socketAddress));
		}
		catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
	}

	@Test
	public void testIPv6URLEncoding() {
		try {
			final String addressString = "2001:db8:10:11:12:ff00:42:8329";
			final String bracketedAddressString = '[' + addressString + ']';
			final int port = 23453;

			InetAddress address = InetAddress.getByName(addressString);
			InetSocketAddress socketAddress = new InetSocketAddress(address, port);

			assertEquals(bracketedAddressString, NetUtils.ipAddressToUrlString(address));
			assertEquals(bracketedAddressString + ':' + port, NetUtils.ipAddressAndPortToUrlString(address, port));
			assertEquals(bracketedAddressString + ':' + port, NetUtils.socketAddressToUrlString(socketAddress));
		}
		catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
	}

	@Test
	public void testFreePortRangeUtility() {
		// inspired by Hadoop's example for "yarn.app.mapreduce.am.job.client.port-range"
		String rangeDefinition = "50000-50050, 50100-50200,51234 "; // this also contains some whitespaces
		Set<Integer> ports = NetUtils.getPortRangeFromString(rangeDefinition);
		Assert.assertEquals(51+101+1, ports.size());
		// check first range
		Assert.assertThat(ports, hasItems(50000, 50001, 50002, 50050));
		// check second range and last point
		Assert.assertThat(ports, hasItems(50100, 50101, 50110, 50200, 51234));
		// check that only ranges are included
		Assert.assertThat(ports, not(hasItems(50051, 50052, 1337, 50201, 49999, 50099)));


		// test single port "range":
		ports = NetUtils.getPortRangeFromString(" 51234");
		Assert.assertEquals(1, ports.size());
		Assert.assertEquals(51234, (int)ports.iterator().next());

		// test port list
		ports = NetUtils.getPortRangeFromString("5,1,2,3,4");
		Assert.assertEquals(5, ports.size());
		Assert.assertThat(ports, hasItems(1,2,3,4,5));


		Throwable error = null;

		// try some wrong values: String
		try { NetUtils.getPortRangeFromString("localhost"); } catch(Throwable t) { error = t; }
		Assert.assertTrue(error instanceof NumberFormatException);
		error = null;

		// incomplete range
		try { NetUtils.getPortRangeFromString("5-"); } catch(Throwable t) { error = t; }
		Assert.assertTrue(error instanceof NumberFormatException);
		error = null;

		// incomplete range
		try { NetUtils.getPortRangeFromString("-5"); } catch(Throwable t) { error = t; }
		Assert.assertTrue(error instanceof NumberFormatException);
		error = null;

		// empty range
		try { NetUtils.getPortRangeFromString(",5"); } catch(Throwable t) { error = t; }
		Assert.assertTrue(error instanceof NumberFormatException);
		error = null;

	}
}
