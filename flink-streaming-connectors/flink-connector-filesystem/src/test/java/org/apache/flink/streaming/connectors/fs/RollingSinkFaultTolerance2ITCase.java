/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.flink.streaming.connectors.fs;

import com.google.common.collect.Sets;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.streaming.api.checkpoint.CheckpointedAsynchronously;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.source.RichParallelSourceFunction;
import org.apache.flink.test.checkpointing.StreamFaultToleranceTestBase;
import org.apache.flink.util.NetUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
* Tests for {@link RollingSink}.
*
* <p>
* This test only verifies the exactly once behaviour of the sink. Another test tests the
* rolling behaviour.
*
* <p>
* This differs from RollingSinkFaultToleranceITCase in that the checkpoint interval is extremely
* high. This provokes the case that the sink restarts without any checkpoint having been performed.
* This tests the initial cleanup of pending/in-progress files.
*/
public class RollingSinkFaultTolerance2ITCase extends StreamFaultToleranceTestBase {

	final long NUM_STRINGS = 16_000;

	@ClassRule
	public static TemporaryFolder tempFolder = new TemporaryFolder();

	private static MiniDFSCluster hdfsCluster;
	private static org.apache.hadoop.fs.FileSystem dfs;

	private static String outPath;



	@BeforeClass
	public static void createHDFS() throws IOException {
		Configuration conf = new Configuration();

		File dataDir = tempFolder.newFolder();

		conf.set(MiniDFSCluster.HDFS_MINIDFS_BASEDIR, dataDir.getAbsolutePath());
		MiniDFSCluster.Builder builder = new MiniDFSCluster.Builder(conf);
		hdfsCluster = builder.build();

		dfs = hdfsCluster.getFileSystem();

		outPath = "hdfs://"
				+ NetUtils.hostAndPortToUrlString(hdfsCluster.getURI().getHost(),  hdfsCluster.getNameNodePort())
				+ "/string-non-rolling-out-no-checkpoint";
	}

	@AfterClass
	public static void destroyHDFS() {
		if (hdfsCluster != null) {
			hdfsCluster.shutdown();
		}
	}


	@Override
	public void testProgram(StreamExecutionEnvironment env) {
		assertTrue("Broken test setup", NUM_STRINGS % 40 == 0);

		int PARALLELISM = 6;

		env.enableCheckpointing(Long.MAX_VALUE);
		env.setParallelism(PARALLELISM);
		env.disableOperatorChaining();

		DataStream<String> stream = env.addSource(new StringGeneratingSourceFunction(NUM_STRINGS)).startNewChain();

		DataStream<String> mapped = stream
				.map(new OnceFailingIdentityMapper(NUM_STRINGS));

		RollingSink<String> sink = new RollingSink<String>(outPath)
				.setBucketer(new NonRollingBucketer())
				.setBatchSize(5000)
				.setValidLengthPrefix("")
				.setPendingPrefix("");

		mapped.addSink(sink);

	}

	@Override
	public void postSubmit() throws Exception {
		// We read the files and verify that we have read all the strings. If a valid-length
		// file exists we only read the file to that point. (This test should work with
		// FileSystems that support truncate() and with others as well.)

		Pattern messageRegex = Pattern.compile("message (\\d*)");

		// Keep a set of the message IDs that we read. The size must equal the read count and
		// the NUM_STRINGS. If numRead is bigger than the size of the set we have seen some
		// elements twice.
		Set<Integer> readNumbers = Sets.newHashSet();
		int numRead = 0;

		RemoteIterator<LocatedFileStatus> files = dfs.listFiles(new Path(
				outPath), true);

		while (files.hasNext()) {
			LocatedFileStatus file = files.next();

			if (!file.getPath().toString().endsWith(".valid-length")) {
				int validLength = (int) file.getLen();
				if (dfs.exists(file.getPath().suffix(".valid-length"))) {
					FSDataInputStream inStream = dfs.open(file.getPath().suffix(".valid-length"));
					String validLengthString = inStream.readUTF();
					validLength = Integer.parseInt(validLengthString);
					System.out.println("VALID LENGTH: " + validLength);
				}
				FSDataInputStream inStream = dfs.open(file.getPath());
				byte[] buffer = new byte[validLength];
				inStream.readFully(0, buffer, 0, validLength);
				inStream.close();

				ByteArrayInputStream bais = new ByteArrayInputStream(buffer);

				InputStreamReader inStreamReader = new InputStreamReader(bais);
				BufferedReader br = new BufferedReader(inStreamReader);

				String line = br.readLine();
				while (line != null) {
					Matcher matcher = messageRegex.matcher(line);
					if (matcher.matches()) {
						numRead++;
						int messageId = Integer.parseInt(matcher.group(1));
						readNumbers.add(messageId);
					} else {
						Assert.fail("Read line does not match expected pattern.");
					}
					line = br.readLine();
				}
				br.close();
				inStreamReader.close();
				bais.close();
			}
		}

		// Verify that we read all strings (at-least-once)
		Assert.assertEquals(NUM_STRINGS, readNumbers.size());

		// Verify that we don't have duplicates (boom!, exactly-once)
		Assert.assertEquals(NUM_STRINGS, numRead);
	}

	private static class OnceFailingIdentityMapper extends RichMapFunction<String, String> {
		private static final long serialVersionUID = 1L;

		private static volatile boolean hasFailed = false;

		private final long numElements;

		private long failurePos;
		private long count;


		OnceFailingIdentityMapper(long numElements) {
			this.numElements = numElements;
		}

		@Override
		public void open(org.apache.flink.configuration.Configuration parameters) throws IOException {
			long failurePosMin = (long) (0.4 * numElements / getRuntimeContext().getNumberOfParallelSubtasks());
			long failurePosMax = (long) (0.7 * numElements / getRuntimeContext().getNumberOfParallelSubtasks());

			failurePos = (new Random().nextLong() % (failurePosMax - failurePosMin)) + failurePosMin;
			count = 0;
		}

		@Override
		public String map(String value) throws Exception {
			count++;
			if (!hasFailed && count >= failurePos) {
				hasFailed = true;
				throw new Exception("Test Failure");
			}

			return value;
		}
	}

	private static class StringGeneratingSourceFunction extends RichParallelSourceFunction<String>
			implements CheckpointedAsynchronously<Integer> {

		private static final long serialVersionUID = 1L;

		private final long numElements;

		private int index;

		private volatile boolean isRunning = true;


		StringGeneratingSourceFunction(long numElements) {
			this.numElements = numElements;
		}

		@Override
		public void run(SourceContext<String> ctx) throws Exception {
			final Object lockingObject = ctx.getCheckpointLock();

			final int step = getRuntimeContext().getNumberOfParallelSubtasks();

			if (index == 0) {
				index = getRuntimeContext().getIndexOfThisSubtask();
			}

			while (isRunning && index < numElements) {

				Thread.sleep(1);
				synchronized (lockingObject) {
					ctx.collect("message " + index);
					index += step;
				}
			}
		}

		@Override
		public void cancel() {
			isRunning = false;
		}

		private static String randomString(StringBuilder bld, Random rnd) {
			final int len = rnd.nextInt(10) + 5;

			for (int i = 0; i < len; i++) {
				char next = (char) (rnd.nextInt(20000) + 33);
				bld.append(next);
			}

			return bld.toString();
		}

		@Override
		public Integer snapshotState(long checkpointId, long checkpointTimestamp) {
			return index;
		}

		@Override
		public void restoreState(Integer state) {
			index = state;
		}
	}
}
