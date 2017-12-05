/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.test.hamcrest;

import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestBuilder;
import org.elasticsearch.action.ShardOperationFailedException;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequestBuilder;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.indices.alias.exists.AliasesExistResponse;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequestBuilder;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexResponse;
import org.elasticsearch.action.admin.indices.template.get.GetIndexTemplatesResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.support.broadcast.BroadcastResponse;
import org.elasticsearch.action.support.master.AcknowledgedRequestBuilder;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.cluster.block.ClusterBlock;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.NamedWriteableAwareStreamInput;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Streamable;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.VersionUtils;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Matchers;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static java.util.Collections.emptyList;
import static org.apache.lucene.util.LuceneTestCase.random;
import static org.elasticsearch.test.VersionUtils.randomVersion;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ElasticsearchAssertions {

    public static void assertAcked(AcknowledgedRequestBuilder<?, ?, ?> builder) {
        assertAcked(builder.get());
    }

    public static void assertNoTimeout(ClusterHealthRequestBuilder requestBuilder) {
        assertNoTimeout(requestBuilder.get());
    }

    public static void assertNoTimeout(ClusterHealthResponse response) {
        assertThat("ClusterHealthResponse has timed out - returned: [" + response + "]", response.isTimedOut(), is(false));
    }

    public static void assertAcked(AcknowledgedResponse response) {
        assertThat(response.getClass().getSimpleName() + " failed - not acked", response.isAcknowledged(), equalTo(true));
        assertVersionSerializable(response);
    }

    public static void assertAcked(DeleteIndexRequestBuilder builder) {
        assertAcked(builder.get());
    }

    public static void assertAcked(DeleteIndexResponse response) {
        assertThat("Delete Index failed - not acked", response.isAcknowledged(), equalTo(true));
        assertVersionSerializable(response);
    }

    /**
     * Assert that an index creation was fully acknowledged, meaning that both the index creation cluster
     * state update was successful and that the requisite number of shard copies were started before returning.
     */
    public static void assertAcked(CreateIndexResponse response) {
        assertThat(response.getClass().getSimpleName() + " failed - not acked", response.isAcknowledged(), equalTo(true));
        assertVersionSerializable(response);
        assertTrue(response.getClass().getSimpleName() + " failed - index creation acked but not all shards were started",
            response.isShardsAcked());
    }

    /**
     * Executes the request and fails if the request has not been blocked.
     *
     * @param builder the request builder
     */
    public static void assertBlocked(ActionRequestBuilder builder) {
        assertBlocked(builder, null);
    }

    /**
     * Checks that all shard requests of a replicated broadcast request failed due to a cluster block
     *
     * @param replicatedBroadcastResponse the response that should only contain failed shard responses
     *
     * */
    public static void assertBlocked(BroadcastResponse replicatedBroadcastResponse) {
        assertThat("all shard requests should have failed", replicatedBroadcastResponse.getFailedShards(), Matchers.equalTo(replicatedBroadcastResponse.getTotalShards()));
        for (ShardOperationFailedException exception : replicatedBroadcastResponse.getShardFailures()) {
            ClusterBlockException clusterBlockException = (ClusterBlockException) ExceptionsHelper.unwrap(exception.getCause(), ClusterBlockException.class);
            assertNotNull("expected the cause of failure to be a ClusterBlockException but got " + exception.getCause().getMessage(), clusterBlockException);
            assertThat(clusterBlockException.blocks().size(), greaterThan(0));
            assertThat(clusterBlockException.status(), CoreMatchers.equalTo(RestStatus.FORBIDDEN));
        }
    }

    /**
     * Executes the request and fails if the request has not been blocked by a specific {@link ClusterBlock}.
     *
     * @param builder the request builder
     * @param expectedBlock the expected block
     */
    public static void assertBlocked(ActionRequestBuilder builder, ClusterBlock expectedBlock) {
        try {
            builder.get();
            fail("Request executed with success but a ClusterBlockException was expected");
        } catch (ClusterBlockException e) {
            assertThat(e.blocks().size(), greaterThan(0));
            assertThat(e.status(), equalTo(RestStatus.FORBIDDEN));

            if (expectedBlock != null) {
                boolean found = false;
                for (ClusterBlock clusterBlock : e.blocks()) {
                    if (clusterBlock.id() == expectedBlock.id()) {
                        found = true;
                        break;
                    }
                }
                assertThat("Request should have been blocked by [" + expectedBlock + "] instead of " + e.blocks(), found, equalTo(true));
            }
        }
    }

    public static String formatShardStatus(BroadcastResponse response) {
        String msg = " Total shards: " + response.getTotalShards() + " Successful shards: " + response.getSuccessfulShards() + " & "
                + response.getFailedShards() + " shard failures:";
        for (ShardOperationFailedException failure : response.getShardFailures()) {
            msg += "\n " + failure.toString();
        }
        return msg;
    }


    public static void assertExists(GetResponse response) {
        String message = String.format(Locale.ROOT, "Expected %s/%s/%s to exist, but does not", response.getIndex(), response.getType(), response.getId());
        assertThat(message, response.isExists(), is(true));
    }


    public static void assertNoFailures(BroadcastResponse response) {
        assertThat("Unexpected ShardFailures: " + Arrays.toString(response.getShardFailures()), response.getFailedShards(), equalTo(0));
        assertVersionSerializable(response);
    }

    public static void assertAllSuccessful(BroadcastResponse response) {
        assertNoFailures(response);
        assertThat("Expected all shards successful but got successful [" + response.getSuccessfulShards() + "] total [" + response.getTotalShards() + "]",
                response.getTotalShards(), equalTo(response.getSuccessfulShards()));
        assertVersionSerializable(response);
    }

    /**
     * Assert that an index template is missing
     */
    public static void assertIndexTemplateMissing(GetIndexTemplatesResponse templatesResponse, String name) {
        List<String> templateNames = new ArrayList<>();
        for (IndexTemplateMetaData indexTemplateMetaData : templatesResponse.getIndexTemplates()) {
            templateNames.add(indexTemplateMetaData.name());
        }
        assertThat(templateNames, not(hasItem(name)));
    }

    /**
     * Assert that an index template exists
     */
    public static void assertIndexTemplateExists(GetIndexTemplatesResponse templatesResponse, String name) {
        List<String> templateNames = new ArrayList<>();
        for (IndexTemplateMetaData indexTemplateMetaData : templatesResponse.getIndexTemplates()) {
            templateNames.add(indexTemplateMetaData.name());
        }
        assertThat(templateNames, hasItem(name));
    }

    /**
     * Assert that aliases are missing
     */
    public static void assertAliasesMissing(AliasesExistResponse aliasesExistResponse) {
        assertFalse("Aliases shouldn't exist", aliasesExistResponse.exists());
    }

    /**
     * Assert that aliases exist
     */
    public static void assertAliasesExist(AliasesExistResponse aliasesExistResponse) {
        assertTrue("Aliases should exist", aliasesExistResponse.exists());
    }


    public static <T extends Query> T assertBooleanSubQuery(Query query, Class<T> subqueryType, int i) {
        assertThat(query, instanceOf(BooleanQuery.class));
        BooleanQuery q = (BooleanQuery) query;
        assertThat(q.clauses().size(), greaterThan(i));
        assertThat(q.clauses().get(i).getQuery(), instanceOf(subqueryType));
        return subqueryType.cast(q.clauses().get(i).getQuery());
    }

    /**
     * Run the request from a given builder and check that it throws an exception of the right type
     */
    public static <E extends Throwable> void assertThrows(ActionRequestBuilder<?, ?, ?> builder, Class<E> exceptionClass) {
        assertThrows(builder.execute(), exceptionClass);
    }

    /**
     * Run the request from a given builder and check that it throws an exception of the right type, with a given {@link org.elasticsearch.rest.RestStatus}
     */
    public static <E extends Throwable> void assertThrows(ActionRequestBuilder<?, ?, ?> builder, Class<E> exceptionClass, RestStatus status) {
        assertThrows(builder.execute(), exceptionClass, status);
    }

    /**
     * Run the request from a given builder and check that it throws an exception of the right type
     *
     * @param extraInfo extra information to add to the failure message
     */
    public static <E extends Throwable> void assertThrows(ActionRequestBuilder<?, ?, ?> builder, Class<E> exceptionClass, String extraInfo) {
        assertThrows(builder.execute(), exceptionClass, extraInfo);
    }

    /**
     * Run future.actionGet() and check that it throws an exception of the right type
     */
    public static <E extends Throwable> void assertThrows(ActionFuture future, Class<E> exceptionClass) {
        assertThrows(future, exceptionClass, null, null);
    }

    /**
     * Run future.actionGet() and check that it throws an exception of the right type, with a given {@link org.elasticsearch.rest.RestStatus}
     */
    public static <E extends Throwable> void assertThrows(ActionFuture future, Class<E> exceptionClass, RestStatus status) {
        assertThrows(future, exceptionClass, status, null);
    }

    /**
     * Run future.actionGet() and check that it throws an exception of the right type
     *
     * @param extraInfo extra information to add to the failure message
     */
    public static <E extends Throwable> void assertThrows(ActionFuture future, Class<E> exceptionClass, String extraInfo) {
        assertThrows(future, exceptionClass, null, extraInfo);
    }

    /**
     * Run future.actionGet() and check that it throws an exception of the right type, optionally checking the exception's rest status
     *
     * @param exceptionClass expected exception class
     * @param status         {@link org.elasticsearch.rest.RestStatus} to check for. Can be null to disable the check
     * @param extraInfo      extra information to add to the failure message. Can be null.
     */
    public static <E extends Throwable> void assertThrows(ActionFuture future, Class<E> exceptionClass, @Nullable RestStatus status, @Nullable String extraInfo) {
        boolean fail = false;
        extraInfo = extraInfo == null || extraInfo.isEmpty() ? "" : extraInfo + ": ";
        extraInfo += "expected a " + exceptionClass + " exception to be thrown";

        if (status != null) {
            extraInfo += " with status [" + status + "]";
        }

        try {
            future.actionGet();
            fail = true;

        } catch (ElasticsearchException esException) {
            assertThat(extraInfo, esException.unwrapCause(), instanceOf(exceptionClass));
            if (status != null) {
                assertThat(extraInfo, ExceptionsHelper.status(esException), equalTo(status));
            }
        } catch (Exception e) {
            assertThat(extraInfo, e, instanceOf(exceptionClass));
            if (status != null) {
                assertThat(extraInfo, ExceptionsHelper.status(e), equalTo(status));
            }
        }
        // has to be outside catch clause to get a proper message
        if (fail) {
            throw new AssertionError(extraInfo);
        }
    }

    public static <E extends Throwable> void assertThrows(ActionRequestBuilder<?, ?, ?> builder, RestStatus status) {
        assertThrows(builder.execute(), status);
    }

    public static <E extends Throwable> void assertThrows(ActionRequestBuilder<?, ?, ?> builder, RestStatus status, String extraInfo) {
        assertThrows(builder.execute(), status, extraInfo);
    }

    public static <E extends Throwable> void assertThrows(ActionFuture future, RestStatus status) {
        assertThrows(future, status, null);
    }

    public static void assertThrows(ActionFuture future, RestStatus status, String extraInfo) {
        boolean fail = false;
        extraInfo = extraInfo == null || extraInfo.isEmpty() ? "" : extraInfo + ": ";
        extraInfo += "expected a " + status + " status exception to be thrown";

        try {
            future.actionGet();
            fail = true;
        } catch (Exception e) {
            assertThat(extraInfo, ExceptionsHelper.status(e), equalTo(status));
        }
        // has to be outside catch clause to get a proper message
        if (fail) {
            throw new AssertionError(extraInfo);
        }
    }

    private static BytesReference serialize(Version version, Streamable streamable) throws IOException {
        BytesStreamOutput output = new BytesStreamOutput();
        output.setVersion(version);
        streamable.writeTo(output);
        output.flush();
        return output.bytes();
    }

    public static void assertVersionSerializable(Streamable streamable) {
        assertTrue(Version.CURRENT.after(VersionUtils.getPreviousVersion()));
        assertVersionSerializable(randomVersion(random()), streamable);
    }

    public static void assertVersionSerializable(Version version, Streamable streamable) {
        /*
         * If possible we fetch the NamedWriteableRegistry from the test cluster. That is the only way to make sure that we properly handle
         * when plugins register names. If not possible we'll try and set up a registry based on whatever SearchModule registers. But that
         * is a hack at best - it only covers some things. If you end up with errors below and get to this comment I'm sorry. Please find
         * a way that sucks less.
         */
        NamedWriteableRegistry registry;
        if (ESIntegTestCase.isInternalCluster() && ESIntegTestCase.internalCluster().size() > 0) {
            registry = ESIntegTestCase.internalCluster().getInstance(NamedWriteableRegistry.class);
        } else {
            registry = new NamedWriteableRegistry(emptyList());
        }
        assertVersionSerializable(version, streamable, registry);
    }

    public static void assertVersionSerializable(Version version, Streamable streamable, NamedWriteableRegistry namedWriteableRegistry) {
        try {
            Streamable newInstance = tryCreateNewInstance(streamable);
            if (newInstance == null) {
                return; // can't create a new instance - we never modify a
                // streamable that comes in.
            }
            if (streamable instanceof ActionRequest) {
                ((ActionRequest) streamable).validate();
            }
            BytesReference orig;
            try {
                orig = serialize(version, streamable);
            } catch (IllegalArgumentException e) {
                // Can't serialize with this version so skip this test.
                return;
            }
            StreamInput input = orig.streamInput();
            if (namedWriteableRegistry != null) {
                input = new NamedWriteableAwareStreamInput(input, namedWriteableRegistry);
            }
            input.setVersion(version);
            newInstance.readFrom(input);
            assertThat("Stream should be fully read with version [" + version + "] for streamable [" + streamable + "]", input.available(),
                    equalTo(0));
            BytesReference newBytes = serialize(version, streamable);
            if (false == orig.equals(newBytes)) {
                // The bytes are different. That is a failure. Lets try to throw a useful exception for debugging.
                String message = "Serialization failed with version [" + version + "] bytes should be equal for streamable [" + streamable
                        + "]";
                // If the bytes are different then comparing BytesRef's toStrings will show you *where* they are different
                assertEquals(message, orig.toBytesRef().toString(), newBytes.toBytesRef().toString());
                // They bytes aren't different. Very very weird.
                fail(message);
            }
        } catch (Exception ex) {
            throw new RuntimeException("failed to check serialization - version [" + version + "] for streamable [" + streamable + "]", ex);
        }

    }

    public static void assertVersionSerializable(Version version, final Exception e) {
        ElasticsearchAssertions.assertVersionSerializable(version, new ExceptionWrapper(e));
    }

    public static final class ExceptionWrapper implements Streamable {

        private Exception exception;

        public ExceptionWrapper(Exception e) {
            exception = e;
        }

        public ExceptionWrapper() {
            exception = null;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            exception = in.readException();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeException(exception);
        }

    }


    private static Streamable tryCreateNewInstance(Streamable streamable) throws NoSuchMethodException, InstantiationException,
            IllegalAccessException, InvocationTargetException {
        try {
            Class<? extends Streamable> clazz = streamable.getClass();
            Constructor<? extends Streamable> constructor = clazz.getConstructor();
            assertThat(constructor, Matchers.notNullValue());
            Streamable newInstance = constructor.newInstance();
            return newInstance;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Check if a file exists
     */
    public static void assertFileExists(Path file) {
        assertThat("file/dir [" + file + "] should exist.", Files.exists(file), is(true));
    }

    /**
     * Check if a file does not exist
     */
    public static void assertFileNotExists(Path file) {
        assertThat("file/dir [" + file + "] should not exist.", Files.exists(file), is(false));
    }

    /**
     * Check if a directory exists
     */
    public static void assertDirectoryExists(Path dir) {
        assertFileExists(dir);
        assertThat("file [" + dir + "] should be a directory.", Files.isDirectory(dir), is(true));
    }

    /**
     * Asserts that the provided {@link BytesReference}s created through
     * {@link org.elasticsearch.common.xcontent.ToXContent#toXContent(XContentBuilder, ToXContent.Params)} hold the same content.
     * The comparison is done by parsing both into a map and comparing those two, so that keys ordering doesn't matter.
     * Also binary values (byte[]) are properly compared through arrays comparisons.
     */
    public static void assertToXContentEquivalent(BytesReference expected, BytesReference actual, XContentType xContentType)
            throws IOException {
        //we tried comparing byte per byte, but that didn't fly for a couple of reasons:
        //1) whenever anything goes through a map while parsing, ordering is not preserved, which is perfectly ok
        //2) Jackson SMILE parser parses floats as double, which then get printed out as double (with double precision)
        //Note that byte[] holding binary values need special treatment as they need to be properly compared item per item.
        try (XContentParser actualParser = xContentType.xContent().createParser(NamedXContentRegistry.EMPTY, actual)) {
            Map<String, Object> actualMap = actualParser.map();
            try (XContentParser expectedParser = xContentType.xContent().createParser(NamedXContentRegistry.EMPTY, expected)) {
                Map<String, Object> expectedMap = expectedParser.map();
                assertMapEquals(expectedMap, actualMap);
            }
        }
    }

    /**
     * Compares two maps recursively, using arrays comparisons for byte[] through Arrays.equals(byte[], byte[])
     */
    @SuppressWarnings("unchecked")
    private static void assertMapEquals(Map<String, Object> expected, Map<String, Object> actual) {
        assertEquals(expected.size(), actual.size());
        for (Map.Entry<String, Object> expectedEntry : expected.entrySet()) {
            String expectedKey = expectedEntry.getKey();
            Object expectedValue = expectedEntry.getValue();
            if (expectedValue == null) {
                assertTrue(actual.get(expectedKey) == null && actual.containsKey(expectedKey));
            } else {
                Object actualValue = actual.get(expectedKey);
                assertObjectEquals(expectedValue, actualValue);
            }
        }
    }

    /**
     * Compares two lists recursively, but using arrays comparisons for byte[] through Arrays.equals(byte[], byte[])
     */
    @SuppressWarnings("unchecked")
    private static void assertListEquals(List<Object> expected, List<Object> actual) {
        assertEquals(expected.size(), actual.size());
        Iterator<Object> actualIterator = actual.iterator();
        for (Object expectedValue : expected) {
            Object actualValue = actualIterator.next();
            assertObjectEquals(expectedValue, actualValue);
        }
    }

    /**
     * Compares two objects, recursively walking eventual maps and lists encountered, and using arrays comparisons
     * for byte[] through Arrays.equals(byte[], byte[])
     */
    @SuppressWarnings("unchecked")
    private static void assertObjectEquals(Object expected, Object actual) {
        if (expected instanceof Map) {
            assertThat(actual, instanceOf(Map.class));
            assertMapEquals((Map<String, Object>) expected, (Map<String, Object>) actual);
        } else if (expected instanceof List) {
            assertListEquals((List<Object>) expected, (List<Object>) actual);
        } else if (expected instanceof byte[]) {
            //byte[] is really a special case for binary values when comparing SMILE and CBOR, arrays of other types
            //don't need to be handled. Ordinary arrays get parsed as lists.
            assertArrayEquals((byte[]) expected, (byte[]) actual);
        } else {
            assertEquals(expected, actual);
        }
    }
}
