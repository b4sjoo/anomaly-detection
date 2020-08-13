/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amazon.opendistroforelasticsearch.ad.ml;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap.SimpleEntry;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.monitor.jvm.JvmService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.modules.junit4.PowerMockRunnerDelegate;

import com.amazon.opendistroforelasticsearch.ad.common.exception.LimitExceededException;
import com.amazon.opendistroforelasticsearch.ad.common.exception.ResourceNotFoundException;
import com.amazon.opendistroforelasticsearch.ad.ml.rcf.CombinedRcfResult;
import com.amazon.opendistroforelasticsearch.ad.model.AnomalyDetector;
import com.amazon.opendistroforelasticsearch.ad.util.DiscoveryNodeFilterer;
import com.amazon.randomcutforest.RandomCutForest;
import com.amazon.randomcutforest.serialize.RandomCutForestSerDe;
import com.google.gson.Gson;

@PowerMockIgnore("javax.management.*")
@RunWith(PowerMockRunner.class)
@PowerMockRunnerDelegate(JUnitParamsRunner.class)
@PrepareForTest({ Gson.class, ClusterSettings.class })
@SuppressWarnings("unchecked")
public class ModelManagerTests {

    private ModelManager modelManager;

    @Mock
    private AnomalyDetector anomalyDetector;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private DiscoveryNodeFilterer nodeFilter;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private JvmService jvmService;

    @Mock
    private RandomCutForestSerDe rcfSerde;

    @Mock
    private CheckpointDao checkpointDao;

    @Mock
    private Clock clock;

    private Gson gson;

    private double modelDesiredSizePercentage;
    private double modelMaxSizePercentage;
    private int numTrees;
    private int numSamples;
    private int numFeatures;
    private double rcfTimeDecay;
    private int numMinSamples;
    private double thresholdMinPvalue;
    private double thresholdMaxRankError;
    private double thresholdMaxScore;
    private int thresholdNumLogNormalQuantiles;
    private int thresholdDownsamples;
    private long thresholdMaxSamples;
    private Class<? extends ThresholdingModel> thresholdingModelClass;
    private int minPreviewSize;
    private Duration modelTtl;
    private Duration checkpointInterval;

    private RandomCutForest rcf;

    @Mock
    private HybridThresholdingModel hybridThresholdingModel;

    private String detectorId;
    private String modelId;
    private String rcfModelId;
    private String thresholdModelId;
    private String checkpoint;
    private int shingleSize;
    private Settings settings;
    private ClusterService clusterService;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        modelDesiredSizePercentage = 0.001;
        modelMaxSizePercentage = 0.1;
        numTrees = 100;
        numSamples = 10;
        numFeatures = 1;
        rcfTimeDecay = 1.0 / 1024;
        numMinSamples = 1;
        thresholdMinPvalue = 0.95;
        thresholdMaxRankError = 1e-4;
        thresholdMaxScore = 8.0;
        thresholdNumLogNormalQuantiles = 10000;
        thresholdDownsamples = 1_000_000;
        thresholdMaxSamples = 2_000_000;
        thresholdingModelClass = HybridThresholdingModel.class;
        minPreviewSize = 500;
        modelTtl = Duration.ofHours(1);
        checkpointInterval = Duration.ofHours(1);
        shingleSize = 1;

        rcf = RandomCutForest.builder().dimensions(numFeatures).sampleSize(numSamples).numberOfTrees(numTrees).build();

        when(jvmService.info().getMem().getHeapMax().getBytes()).thenReturn(10_000_000_000L);

        when(anomalyDetector.getShingleSize()).thenReturn(shingleSize);

        gson = PowerMockito.mock(Gson.class);

        settings = Settings.builder().put("opendistro.anomaly_detection.model_max_size_percent", modelMaxSizePercentage).build();
        ClusterSettings clusterSettings = PowerMockito.mock(ClusterSettings.class);

        clusterService = new ClusterService(settings, clusterSettings, null);

        modelManager = spy(
            new ModelManager(
                nodeFilter,
                jvmService,
                rcfSerde,
                checkpointDao,
                gson,
                clock,
                modelDesiredSizePercentage,
                modelMaxSizePercentage,
                numTrees,
                numSamples,
                rcfTimeDecay,
                numMinSamples,
                thresholdMinPvalue,
                thresholdMaxRankError,
                thresholdMaxScore,
                thresholdNumLogNormalQuantiles,
                thresholdDownsamples,
                thresholdMaxSamples,
                thresholdingModelClass,
                minPreviewSize,
                modelTtl,
                checkpointInterval,
                clusterService
            )
        );

        detectorId = "detectorId";
        modelId = "modelId";
        rcfModelId = "detectorId_model_rcf_1";
        thresholdModelId = "detectorId_model_threshold";
        checkpoint = "testcheckpoint";
    }

    private Object[] getDetectorIdForModelIdData() {
        return new Object[] {
            new Object[] { "testId_model_threshold", "testId" },
            new Object[] { "test_id_model_threshold", "test_id" },
            new Object[] { "test_model_id_model_threshold", "test_model_id" },
            new Object[] { "testId_model_rcf_1", "testId" },
            new Object[] { "test_Id_model_rcf_1", "test_Id" },
            new Object[] { "test_model_rcf_Id_model_rcf_1", "test_model_rcf_Id" }, };
    };

    @Test
    @Parameters(method = "getDetectorIdForModelIdData")
    public void getDetectorIdForModelId_returnExpectedId(String modelId, String expectedDetectorId) {
        assertEquals(expectedDetectorId, modelManager.getDetectorIdForModelId(modelId));
    }

    private Object[] getDetectorIdForModelIdIllegalArgument() {
        return new Object[] { new Object[] { "testId" }, new Object[] { "testid_" }, new Object[] { "_testId" }, };
    }

    @Test(expected = IllegalArgumentException.class)
    @Parameters(method = "getDetectorIdForModelIdIllegalArgument")
    public void getDetectorIdForModelId_throwIllegalArgument_forInvalidId(String modelId) {
        modelManager.getDetectorIdForModelId(modelId);
    }

    private Object[] combineRcfResultsData() {
        return new Object[] {
            new Object[] { asList(), new CombinedRcfResult(0, 0) },
            new Object[] { asList(new RcfResult(0, 0, 0)), new CombinedRcfResult(0, 0) },
            new Object[] { asList(new RcfResult(1, 0, 50)), new CombinedRcfResult(1, 0) },
            new Object[] { asList(new RcfResult(1, 0, 50), new RcfResult(2, 0, 50)), new CombinedRcfResult(1.5, 0) },
            new Object[] {
                asList(new RcfResult(1, 0, 40), new RcfResult(2, 0, 60), new RcfResult(3, 0, 100)),
                new CombinedRcfResult(2.3, 0) },
            new Object[] { asList(new RcfResult(0, 1, 100)), new CombinedRcfResult(0, 1) },
            new Object[] { asList(new RcfResult(0, 1, 50)), new CombinedRcfResult(0, 0.5) },
            new Object[] { asList(new RcfResult(0, 0.5, 1000)), new CombinedRcfResult(0, 0.5) },
            new Object[] { asList(new RcfResult(0, 1, 50), new RcfResult(0, 0, 50)), new CombinedRcfResult(0, 0.5) },
            new Object[] { asList(new RcfResult(0, 0.5, 50), new RcfResult(0, 0.5, 50)), new CombinedRcfResult(0, 0.5) },
            new Object[] {
                asList(new RcfResult(0, 1, 20), new RcfResult(0, 1, 30), new RcfResult(0, 0.5, 50)),
                new CombinedRcfResult(0, 0.75) }, };
    }

    @Test
    @Parameters(method = "combineRcfResultsData")
    public void combineRcfResults_returnExpected(List<RcfResult> results, CombinedRcfResult expected) {
        assertEquals(expected, modelManager.combineRcfResults(results));
    }

    private ImmutableOpenMap<String, DiscoveryNode> createDataNodes(int numDataNodes) {
        ImmutableOpenMap.Builder<String, DiscoveryNode> dataNodes = ImmutableOpenMap.builder();
        for (int i = 0; i < numDataNodes; i++) {
            dataNodes.put("foo" + i, mock(DiscoveryNode.class));
        }
        return dataNodes.build();
    }

    private Object[] getPartitionedForestSizesData() {
        return new Object[] {
            // one partition given sufficient large nodes
            new Object[] { 100L, 100_000L, createDataNodes(10), pair(1, 100) },
            // two paritions given sufficient medium nodes
            new Object[] { 100L, 50_000L, createDataNodes(10), pair(2, 50) },
            // ten partitions given sufficent small nodes
            new Object[] { 100L, 10_000L, createDataNodes(10), pair(10, 10) },
            // five double-sized paritions given fewer small nodes
            new Object[] { 100L, 10_000L, createDataNodes(5), pair(5, 20) },
            // one large-sized partition given one small node
            new Object[] { 100L, 1_000L, createDataNodes(1), pair(1, 100) } };
    }

    @Test
    @Parameters(method = "getPartitionedForestSizesData")
    public void getPartitionedForestSizes_returnExpected(
        long totalModelSize,
        long heapSize,
        ImmutableOpenMap<String, DiscoveryNode> dataNodes,
        Entry<Integer, Integer> expected
    ) {

        when(modelManager.estimateModelSize(rcf)).thenReturn(totalModelSize);
        when(jvmService.info().getMem().getHeapMax().getBytes()).thenReturn(heapSize);
        when(nodeFilter.getEligibleDataNodes()).thenReturn(dataNodes.values().toArray(DiscoveryNode.class));

        assertEquals(expected, modelManager.getPartitionedForestSizes(rcf, "id"));
    }

    private Object[] getPartitionedForestSizesLimitExceededData() {
        return new Object[] {
            new Object[] { 101L, 1_000L, createDataNodes(1) },
            new Object[] { 201L, 1_000L, createDataNodes(2) },
            new Object[] { 3001L, 10_000L, createDataNodes(3) } };
    }

    @Test(expected = LimitExceededException.class)
    @Parameters(method = "getPartitionedForestSizesLimitExceededData")
    public void getPartitionedForestSizes_throwLimitExceeded(
        long totalModelSize,
        long heapSize,
        ImmutableOpenMap<String, DiscoveryNode> dataNodes
    ) {
        when(modelManager.estimateModelSize(rcf)).thenReturn(totalModelSize);
        when(jvmService.info().getMem().getHeapMax().getBytes()).thenReturn(heapSize);
        when(nodeFilter.getEligibleDataNodes()).thenReturn(dataNodes.values().toArray(DiscoveryNode.class));

        modelManager.getPartitionedForestSizes(rcf, "id");
    }

    private Object[] estimateModelSizeData() {
        return new Object[] {
            new Object[] { RandomCutForest.builder().dimensions(1).sampleSize(256).numberOfTrees(100).build(), 819200L },
            new Object[] { RandomCutForest.builder().dimensions(5).sampleSize(256).numberOfTrees(100).build(), 4096000L } };
    }

    @Parameters(method = "estimateModelSizeData")
    public void estimateModelSize_returnExpected(RandomCutForest rcf, long expectedSize) {
        assertEquals(expectedSize, modelManager.estimateModelSize(rcf));
    }

    @Test
    public void getRcfResult_returnExpected() {
        double[] point = new double[0];
        RandomCutForest forest = mock(RandomCutForest.class);

        double score = 11.;

        when(checkpointDao.getModelCheckpoint(rcfModelId)).thenReturn(Optional.of(checkpoint));
        when(rcfSerde.fromJson(checkpoint)).thenReturn(forest);
        when(forest.getAnomalyScore(point)).thenReturn(score);
        when(forest.getNumberOfTrees()).thenReturn(numTrees);
        when(forest.getLambda()).thenReturn(rcfTimeDecay);
        when(forest.getSampleSize()).thenReturn(numSamples);
        when(forest.getTotalUpdates()).thenReturn((long) numSamples);

        RcfResult result = modelManager.getRcfResult(detectorId, rcfModelId, point);

        RcfResult expected = new RcfResult(score, 0, numTrees);
        assertEquals(expected, result);

        when(forest.getTotalUpdates()).thenReturn(numSamples + 1L);
        result = modelManager.getRcfResult(detectorId, rcfModelId, point);
        assertEquals(0.091353632, result.getConfidence(), 1e-6);

        when(forest.getTotalUpdates()).thenReturn(20_000L);
        result = modelManager.getRcfResult(detectorId, rcfModelId, point);
        assertEquals(1, result.getConfidence(), 1e-6);
    }

    @Test(expected = ResourceNotFoundException.class)
    public void getRcfResult_throwResourceNotFound_whenNoModelCheckpointFound() {
        String detectorId = "testDetectorId";
        String modelId = "testModelId";
        when(checkpointDao.getModelCheckpoint(modelId)).thenReturn(Optional.empty());

        modelManager.getRcfResult(detectorId, modelId, new double[0]);
    }

    @Test(expected = LimitExceededException.class)
    public void getRcfResult_throwLimitExceeded_whenHeapLimitReached() {
        String detectorId = "testDetectorId";
        String modelId = "testModelId";

        when(checkpointDao.getModelCheckpoint(modelId)).thenReturn(Optional.of(checkpoint));
        when(rcfSerde.fromJson(checkpoint)).thenReturn(rcf);
        when(jvmService.info().getMem().getHeapMax().getBytes()).thenReturn(1_000L);

        modelManager.getRcfResult(detectorId, modelId, new double[0]);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void getRcfResult_returnExpectedToListener() {
        double[] point = new double[0];
        RandomCutForest forest = mock(RandomCutForest.class);
        double score = 11.;

        doAnswer(invocation -> {
            ActionListener<Optional<String>> listener = invocation.getArgument(1);
            listener.onResponse(Optional.of(checkpoint));
            return null;
        }).when(checkpointDao).getModelCheckpoint(eq(rcfModelId), any(ActionListener.class));
        when(rcfSerde.fromJson(checkpoint)).thenReturn(forest);
        when(forest.getAnomalyScore(point)).thenReturn(score);
        when(forest.getNumberOfTrees()).thenReturn(numTrees);
        when(forest.getLambda()).thenReturn(rcfTimeDecay);
        when(forest.getSampleSize()).thenReturn(numSamples);
        when(forest.getTotalUpdates()).thenReturn((long) numSamples);

        ActionListener<RcfResult> listener = mock(ActionListener.class);
        modelManager.getRcfResult(detectorId, rcfModelId, point, listener);

        RcfResult expected = new RcfResult(score, 0, numTrees);
        verify(listener).onResponse(eq(expected));

        when(forest.getTotalUpdates()).thenReturn(numSamples + 1L);
        listener = mock(ActionListener.class);
        modelManager.getRcfResult(detectorId, rcfModelId, point, listener);

        ArgumentCaptor<RcfResult> responseCaptor = ArgumentCaptor.forClass(RcfResult.class);
        verify(listener).onResponse(responseCaptor.capture());
        assertEquals(0.091353632, responseCaptor.getValue().getConfidence(), 1e-6);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void getRcfResult_throwToListener_whenNoCheckpoint() {
        doAnswer(invocation -> {
            ActionListener<Optional<String>> listener = invocation.getArgument(1);
            listener.onResponse(Optional.empty());
            return null;
        }).when(checkpointDao).getModelCheckpoint(eq(rcfModelId), any(ActionListener.class));

        ActionListener<RcfResult> listener = mock(ActionListener.class);
        modelManager.getRcfResult(detectorId, rcfModelId, new double[0], listener);

        verify(listener).onFailure(any(ResourceNotFoundException.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void getRcfResult_throwToListener_whenHeapLimitExceed() {
        doAnswer(invocation -> {
            ActionListener<Optional<String>> listener = invocation.getArgument(1);
            listener.onResponse(Optional.of(checkpoint));
            return null;
        }).when(checkpointDao).getModelCheckpoint(eq(rcfModelId), any(ActionListener.class));
        when(rcfSerde.fromJson(checkpoint)).thenReturn(rcf);
        when(jvmService.info().getMem().getHeapMax().getBytes()).thenReturn(1_000L);

        ActionListener<RcfResult> listener = mock(ActionListener.class);
        modelManager.getRcfResult(detectorId, rcfModelId, new double[0], listener);

        verify(listener).onFailure(any(LimitExceededException.class));
    }

    @Test
    public void getThresholdingResult_returnExpected() {
        String modelId = "testModelId";
        double score = 1.;

        double grade = 0.;
        double confidence = 0.5;

        when(checkpointDao.getModelCheckpoint(modelId)).thenReturn(Optional.of(checkpoint));
        PowerMockito.doReturn(hybridThresholdingModel).when(gson).fromJson(checkpoint, thresholdingModelClass);
        when(hybridThresholdingModel.grade(score)).thenReturn(grade);
        when(hybridThresholdingModel.confidence()).thenReturn(confidence);

        ThresholdingResult result = modelManager.getThresholdingResult(detectorId, modelId, score);

        ThresholdingResult expected = new ThresholdingResult(grade, confidence);
        assertEquals(expected, result);

        result = modelManager.getThresholdingResult(detectorId, modelId, score);
        assertEquals(expected, result);
    }

    @Test(expected = ResourceNotFoundException.class)
    public void getThresholdingResult_throwResourceNotFound_whenNoModelCheckpointFound() {
        String modelId = "testModelId";
        when(checkpointDao.getModelCheckpoint(modelId)).thenReturn(Optional.empty());

        modelManager.getThresholdingResult("testDetectorId", modelId, 1.);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void getThresholdingResult_returnExpectedToListener() {
        double score = 1.;
        double grade = 0.;
        double confidence = 0.5;

        doAnswer(invocation -> {
            ActionListener<Optional<String>> listener = invocation.getArgument(1);
            listener.onResponse(Optional.of(checkpoint));
            return null;
        }).when(checkpointDao).getModelCheckpoint(eq(thresholdModelId), any(ActionListener.class));
        PowerMockito.doReturn(hybridThresholdingModel).when(gson).fromJson(checkpoint, thresholdingModelClass);
        when(hybridThresholdingModel.grade(score)).thenReturn(grade);
        when(hybridThresholdingModel.confidence()).thenReturn(confidence);

        ActionListener<ThresholdingResult> listener = mock(ActionListener.class);
        modelManager.getThresholdingResult(detectorId, thresholdModelId, score, listener);

        ThresholdingResult expected = new ThresholdingResult(grade, confidence);
        verify(listener).onResponse(eq(expected));

        listener = mock(ActionListener.class);
        modelManager.getThresholdingResult(detectorId, thresholdModelId, score, listener);
        verify(listener).onResponse(eq(expected));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void getThresholdingResult_throwToListener_withNoCheckpoint() {
        doAnswer(invocation -> {
            ActionListener<Optional<String>> listener = invocation.getArgument(1);
            listener.onResponse(Optional.empty());
            return null;
        }).when(checkpointDao).getModelCheckpoint(eq(thresholdModelId), any(ActionListener.class));

        ActionListener<ThresholdingResult> listener = mock(ActionListener.class);
        modelManager.getThresholdingResult(detectorId, thresholdModelId, 0, listener);

        verify(listener).onFailure(any(ResourceNotFoundException.class));
    }

    @Test
    public void getThresholdingResult_notUpdate_withZeroScore() {
        double score = 0.0;

        doAnswer(invocation -> {
            ActionListener<Optional<String>> listener = invocation.getArgument(1);
            listener.onResponse(Optional.of(checkpoint));
            return null;
        }).when(checkpointDao).getModelCheckpoint(eq(thresholdModelId), any(ActionListener.class));
        PowerMockito.doReturn(hybridThresholdingModel).when(gson).fromJson(checkpoint, thresholdingModelClass);

        ActionListener<ThresholdingResult> listener = mock(ActionListener.class);
        modelManager.getThresholdingResult(detectorId, thresholdModelId, score, listener);

        verify(hybridThresholdingModel, never()).update(score);
    }

    @Test
    public void getAllModelIds_returnAllIds_forRcfAndThreshold() {

        when(checkpointDao.getModelCheckpoint(rcfModelId)).thenReturn(Optional.of(checkpoint));
        when(rcfSerde.fromJson(checkpoint)).thenReturn(mock(RandomCutForest.class));
        modelManager.getRcfResult(detectorId, rcfModelId, new double[0]);
        when(checkpointDao.getModelCheckpoint(thresholdModelId)).thenReturn(Optional.of(checkpoint));
        PowerMockito.doReturn(hybridThresholdingModel).when(gson).fromJson(checkpoint, thresholdingModelClass);
        modelManager.getThresholdingResult(detectorId, thresholdModelId, 0);

        assertEquals(Stream.of(rcfModelId, thresholdModelId).collect(Collectors.toSet()), modelManager.getAllModelIds());
    }

    @Test
    public void getAllModelIds_returnEmpty_forNoModels() {
        assertEquals(Collections.emptySet(), modelManager.getAllModelIds());
    }

    @Test
    public void stopModel_saveRcfCheckpoint() {

        RandomCutForest forest = mock(RandomCutForest.class);
        when(checkpointDao.getModelCheckpoint(rcfModelId)).thenReturn(Optional.of(checkpoint));
        when(rcfSerde.fromJson(checkpoint)).thenReturn(forest);
        when(rcfSerde.toJson(forest)).thenReturn(checkpoint);
        modelManager.getRcfResult(detectorId, rcfModelId, new double[0]);
        when(clock.instant()).thenReturn(Instant.EPOCH);

        modelManager.stopModel(detectorId, rcfModelId);

        verify(checkpointDao).putModelCheckpoint(rcfModelId, checkpoint);
    }

    @Test
    public void stopModel_saveThresholdCheckpoint() {

        when(checkpointDao.getModelCheckpoint(thresholdModelId)).thenReturn(Optional.of(checkpoint));
        PowerMockito.doReturn(hybridThresholdingModel).when(gson).fromJson(checkpoint, thresholdingModelClass);
        PowerMockito.doReturn(checkpoint).when(gson).toJson(hybridThresholdingModel);
        modelManager.getThresholdingResult(detectorId, thresholdModelId, 0);
        when(clock.instant()).thenReturn(Instant.EPOCH);

        modelManager.stopModel(detectorId, thresholdModelId);

        verify(checkpointDao).putModelCheckpoint(thresholdModelId, checkpoint);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void stopModel_returnExpectedToListener_whenRcfStop() {
        RandomCutForest forest = mock(RandomCutForest.class);
        when(checkpointDao.getModelCheckpoint(rcfModelId)).thenReturn(Optional.of(checkpoint));
        when(rcfSerde.fromJson(checkpoint)).thenReturn(forest);
        when(rcfSerde.toJson(forest)).thenReturn(checkpoint);
        modelManager.getRcfResult(detectorId, rcfModelId, new double[0]);
        when(clock.instant()).thenReturn(Instant.EPOCH);
        doAnswer(invocation -> {
            ActionListener<Void> listener = invocation.getArgument(2);
            listener.onResponse(null);
            return null;
        }).when(checkpointDao).putModelCheckpoint(eq(rcfModelId), eq(checkpoint), any(ActionListener.class));

        ActionListener<Void> listener = mock(ActionListener.class);
        modelManager.stopModel(detectorId, rcfModelId, listener);

        verify(listener).onResponse(eq(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void stopModel_returnExpectedToListener_whenThresholdStop() {
        when(checkpointDao.getModelCheckpoint(thresholdModelId)).thenReturn(Optional.of(checkpoint));
        PowerMockito.doReturn(hybridThresholdingModel).when(gson).fromJson(checkpoint, thresholdingModelClass);
        PowerMockito.doReturn(checkpoint).when(gson).toJson(hybridThresholdingModel);
        modelManager.getThresholdingResult(detectorId, thresholdModelId, 0);
        when(clock.instant()).thenReturn(Instant.EPOCH);
        doAnswer(invocation -> {
            ActionListener<Void> listener = invocation.getArgument(2);
            listener.onResponse(null);
            return null;
        }).when(checkpointDao).putModelCheckpoint(eq(thresholdModelId), eq(checkpoint), any(ActionListener.class));

        ActionListener<Void> listener = mock(ActionListener.class);
        modelManager.stopModel(detectorId, thresholdModelId, listener);

        verify(listener).onResponse(eq(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void stopModel_throwToListener_whenCheckpointFail() {
        RandomCutForest forest = mock(RandomCutForest.class);
        when(checkpointDao.getModelCheckpoint(rcfModelId)).thenReturn(Optional.of(checkpoint));
        when(rcfSerde.fromJson(checkpoint)).thenReturn(forest);
        when(rcfSerde.toJson(forest)).thenReturn(checkpoint);
        modelManager.getRcfResult(detectorId, rcfModelId, new double[0]);
        when(clock.instant()).thenReturn(Instant.EPOCH);
        doAnswer(invocation -> {
            ActionListener<Void> listener = invocation.getArgument(2);
            listener.onFailure(new RuntimeException());
            return null;
        }).when(checkpointDao).putModelCheckpoint(eq(rcfModelId), eq(checkpoint), any(ActionListener.class));

        ActionListener<Void> listener = mock(ActionListener.class);
        modelManager.stopModel(detectorId, rcfModelId, listener);

        verify(listener).onFailure(any(Exception.class));
    }

    @Test
    public void clear_deleteRcfCheckpoint() {

        RandomCutForest forest = mock(RandomCutForest.class);
        when(checkpointDao.getModelCheckpoint(rcfModelId)).thenReturn(Optional.of(checkpoint));
        when(rcfSerde.fromJson(checkpoint)).thenReturn(forest);
        modelManager.getRcfResult(detectorId, rcfModelId, new double[0]);

        modelManager.clear(detectorId);

        verify(checkpointDao).deleteModelCheckpoint(rcfModelId);
    }

    @Test
    public void clear_deleteThresholdCheckpoint() {

        when(checkpointDao.getModelCheckpoint(thresholdModelId)).thenReturn(Optional.of(checkpoint));
        PowerMockito.doReturn(hybridThresholdingModel).when(gson).fromJson(checkpoint, thresholdingModelClass);
        PowerMockito.doReturn(checkpoint).when(gson).toJson(hybridThresholdingModel);
        modelManager.getThresholdingResult(detectorId, thresholdModelId, 0);

        modelManager.clear(detectorId);

        verify(checkpointDao).deleteModelCheckpoint(thresholdModelId);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void clear_callListener_whenRcfDeleted() {
        String otherModelId = detectorId + rcfModelId;
        RandomCutForest forest = mock(RandomCutForest.class);
        when(checkpointDao.getModelCheckpoint(rcfModelId)).thenReturn(Optional.of(checkpoint));
        when(checkpointDao.getModelCheckpoint(otherModelId)).thenReturn(Optional.of(checkpoint));
        when(rcfSerde.fromJson(checkpoint)).thenReturn(forest);
        modelManager.getRcfResult(detectorId, rcfModelId, new double[0]);
        modelManager.getRcfResult(otherModelId, otherModelId, new double[0]);
        doAnswer(invocation -> {
            ActionListener<Void> listener = invocation.getArgument(1);
            listener.onResponse(null);
            return null;
        }).when(checkpointDao).deleteModelCheckpoint(eq(rcfModelId), any(ActionListener.class));

        ActionListener<Void> listener = mock(ActionListener.class);
        modelManager.clear(detectorId, listener);

        verify(listener).onResponse(null);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void clear_callListener_whenThresholdDeleted() {
        when(checkpointDao.getModelCheckpoint(thresholdModelId)).thenReturn(Optional.of(checkpoint));
        PowerMockito.doReturn(hybridThresholdingModel).when(gson).fromJson(checkpoint, thresholdingModelClass);
        PowerMockito.doReturn(checkpoint).when(gson).toJson(hybridThresholdingModel);
        modelManager.getThresholdingResult(detectorId, thresholdModelId, 0);
        doAnswer(invocation -> {
            ActionListener<Void> listener = invocation.getArgument(1);
            listener.onResponse(null);
            return null;
        }).when(checkpointDao).deleteModelCheckpoint(eq(thresholdModelId), any(ActionListener.class));

        ActionListener<Void> listener = mock(ActionListener.class);
        modelManager.clear(detectorId, listener);

        verify(listener).onResponse(null);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void clear_throwToListener_whenDeleteFail() {
        RandomCutForest forest = mock(RandomCutForest.class);
        when(checkpointDao.getModelCheckpoint(rcfModelId)).thenReturn(Optional.of(checkpoint));
        when(rcfSerde.fromJson(checkpoint)).thenReturn(forest);
        modelManager.getRcfResult(detectorId, rcfModelId, new double[0]);
        doAnswer(invocation -> {
            ActionListener<Void> listener = invocation.getArgument(1);
            listener.onFailure(new RuntimeException());
            return null;
        }).when(checkpointDao).deleteModelCheckpoint(eq(rcfModelId), any(ActionListener.class));

        ActionListener<Void> listener = mock(ActionListener.class);
        modelManager.clear(detectorId, listener);

        verify(listener).onFailure(any(Exception.class));
    }

    @Test
    public void trainModel_putTrainedModels() {
        double[][] trainData = new Random().doubles().limit(100).mapToObj(d -> new double[] { d }).toArray(double[][]::new);
        doReturn(new SimpleEntry<>(1, 10)).when(modelManager).getPartitionedForestSizes(anyObject(), anyObject());
        doReturn(asList("feature1")).when(anomalyDetector).getEnabledFeatureIds();
        modelManager.trainModel(anomalyDetector, trainData);

        verify(checkpointDao).putModelCheckpoint(eq(modelManager.getRcfModelId(anomalyDetector.getDetectorId(), 0)), anyObject());
        verify(checkpointDao).putModelCheckpoint(eq(modelManager.getThresholdModelId(anomalyDetector.getDetectorId())), anyObject());
    }

    private Object[] trainModelIllegalArgumentData() {
        return new Object[] { new Object[] { new double[][] {} }, new Object[] { new double[][] { {} } } };
    }

    @Test(expected = IllegalArgumentException.class)
    @Parameters(method = "trainModelIllegalArgumentData")
    public void trainModel_throwIllegalArgument_forInvalidInput(double[][] trainData) {
        modelManager.trainModel(anomalyDetector, trainData);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void trainModel_returnExpectedToListener_putCheckpoints() {
        double[][] trainData = new Random().doubles().limit(100).mapToObj(d -> new double[] { d }).toArray(double[][]::new);
        doReturn(new SimpleEntry<>(2, 10)).when(modelManager).getPartitionedForestSizes(anyObject(), anyObject());
        doAnswer(invocation -> {
            ActionListener<Void> listener = invocation.getArgument(2);
            listener.onResponse(null);
            return null;
        }).when(checkpointDao).putModelCheckpoint(any(), any(), any(ActionListener.class));

        ActionListener<Void> listener = mock(ActionListener.class);
        modelManager.trainModel(anomalyDetector, trainData, listener);

        verify(listener).onResponse(eq(null));
        verify(checkpointDao, times(3)).putModelCheckpoint(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    @Parameters(method = "trainModelIllegalArgumentData")
    public void trainModel_throwIllegalArgumentToListener_forInvalidTrainData(double[][] trainData) {
        ActionListener<Void> listener = mock(ActionListener.class);
        modelManager.trainModel(anomalyDetector, trainData, listener);

        verify(listener).onFailure(any(IllegalArgumentException.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void trainModel_throwLimitExceededToListener_whenLimitExceed() {
        doThrow(new LimitExceededException(null, null)).when(modelManager).getPartitionedForestSizes(anyObject(), anyObject());

        ActionListener<Void> listener = mock(ActionListener.class);
        modelManager.trainModel(anomalyDetector, new double[][] { { 0 } }, listener);

        verify(listener).onFailure(any(LimitExceededException.class));
    }

    @Test
    public void getRcfModelId_returnNonEmptyString() {
        String rcfModelId = modelManager.getRcfModelId(anomalyDetector.getDetectorId(), 0);

        assertFalse(rcfModelId.isEmpty());
    }

    @Test
    public void getThresholdModelId_returnNonEmptyString() {
        String thresholdModelId = modelManager.getThresholdModelId(anomalyDetector.getDetectorId());

        assertFalse(thresholdModelId.isEmpty());
    }

    private Entry<Integer, Integer> pair(int size, int value) {
        return new SimpleImmutableEntry<>(size, value);
    }

    public void maintenance_doNothing_givenNoModel() {
        modelManager.maintenance();

        verifyZeroInteractions(checkpointDao);
    }

    @Test
    public void maintenance_saveRcfCheckpoint_skippingFailure() {
        String successModelId = "testSuccessModelId";
        String failModelId = "testFailModelId";
        String successCheckpoint = "testSuccessCheckpoint";
        String failCheckpoint = "testFailCheckpoint";
        double[] point = new double[0];
        RandomCutForest forest = mock(RandomCutForest.class);
        RandomCutForest failForest = mock(RandomCutForest.class);

        when(checkpointDao.getModelCheckpoint(successModelId)).thenReturn(Optional.of(successCheckpoint));
        when(checkpointDao.getModelCheckpoint(failModelId)).thenReturn(Optional.of(failCheckpoint));
        when(rcfSerde.fromJson(successCheckpoint)).thenReturn(forest);
        when(rcfSerde.fromJson(failCheckpoint)).thenReturn(failForest);
        when(rcfSerde.toJson(forest)).thenReturn(successCheckpoint);
        when(rcfSerde.toJson(failForest)).thenThrow(new RuntimeException());
        when(clock.instant()).thenReturn(Instant.EPOCH);
        modelManager.getRcfResult(detectorId, successModelId, point);
        modelManager.getRcfResult(detectorId, failModelId, point);

        modelManager.maintenance();

        verify(checkpointDao).putModelCheckpoint(successModelId, successCheckpoint);
    }

    @Test
    public void maintenance_saveThresholdCheckpoint_skippingFailure() {
        String successModelId = "testSuccessModelId";
        String failModelId = "testFailModelId";
        String successCheckpoint = "testSuccessCheckpoint";
        String failCheckpoint = "testFailCheckpoint";
        double score = 1.;
        HybridThresholdingModel failThresholdModel = mock(HybridThresholdingModel.class);
        when(checkpointDao.getModelCheckpoint(successModelId)).thenReturn(Optional.of(successCheckpoint));
        when(checkpointDao.getModelCheckpoint(failModelId)).thenReturn(Optional.of(failCheckpoint));
        doReturn(hybridThresholdingModel).when(gson).fromJson(successCheckpoint, thresholdingModelClass);
        doReturn(failThresholdModel).when(gson).fromJson(failCheckpoint, thresholdingModelClass);
        doReturn(successCheckpoint).when(gson).toJson(hybridThresholdingModel);
        doThrow(new RuntimeException()).when(gson).toJson(failThresholdModel);
        when(clock.instant()).thenReturn(Instant.EPOCH);
        modelManager.getThresholdingResult(detectorId, successModelId, score);
        modelManager.getThresholdingResult(detectorId, failModelId, score);

        modelManager.maintenance();

        verify(checkpointDao).putModelCheckpoint(successModelId, successCheckpoint);
    }

    @Test
    public void maintenance_stopInactiveRcfModel() {
        String modelId = "testModelId";
        double[] point = new double[0];
        RandomCutForest forest = mock(RandomCutForest.class);
        when(checkpointDao.getModelCheckpoint(modelId)).thenReturn(Optional.of(checkpoint));
        when(rcfSerde.fromJson(checkpoint)).thenReturn(forest);
        when(rcfSerde.toJson(forest)).thenReturn(checkpoint);
        when(clock.instant()).thenReturn(Instant.MIN, Instant.EPOCH, Instant.EPOCH.plus(modelTtl).plus(Duration.ofSeconds(1)));
        modelManager.getRcfResult(detectorId, modelId, point);

        modelManager.maintenance();

        modelManager.getRcfResult(detectorId, modelId, point);
        verify(checkpointDao, times(2)).getModelCheckpoint(modelId);
    }

    @Test
    public void maintenance_keepActiveRcfModel() {
        String modelId = "testModelId";
        double[] point = new double[0];
        RandomCutForest forest = mock(RandomCutForest.class);
        when(checkpointDao.getModelCheckpoint(modelId)).thenReturn(Optional.of(checkpoint));
        when(rcfSerde.fromJson(checkpoint)).thenReturn(forest);
        when(rcfSerde.toJson(forest)).thenReturn(checkpoint);
        when(clock.instant()).thenReturn(Instant.MIN, Instant.EPOCH, Instant.EPOCH);
        modelManager.getRcfResult(detectorId, modelId, point);

        modelManager.maintenance();

        modelManager.getRcfResult(detectorId, modelId, point);
        verify(checkpointDao, times(1)).getModelCheckpoint(modelId);
    }

    @Test
    public void maintenance_stopInactiveThresholdModel() {
        String modelId = "testModelId";
        double score = 1.;
        when(checkpointDao.getModelCheckpoint(modelId)).thenReturn(Optional.of(checkpoint));
        doReturn(hybridThresholdingModel).when(gson).fromJson(checkpoint, thresholdingModelClass);
        doReturn(checkpoint).when(gson).toJson(hybridThresholdingModel);
        when(clock.instant()).thenReturn(Instant.MIN, Instant.EPOCH, Instant.EPOCH.plus(modelTtl).plus(Duration.ofSeconds(1)));
        modelManager.getThresholdingResult(detectorId, modelId, score);

        modelManager.maintenance();

        modelManager.getThresholdingResult(detectorId, modelId, score);
        verify(checkpointDao, times(2)).getModelCheckpoint(modelId);
    }

    @Test
    public void maintenance_keepActiveThresholdModel() {
        String modelId = "testModelId";
        double score = 1.;
        when(checkpointDao.getModelCheckpoint(modelId)).thenReturn(Optional.of(checkpoint));
        doReturn(hybridThresholdingModel).when(gson).fromJson(checkpoint, thresholdingModelClass);
        doReturn(checkpoint).when(gson).toJson(hybridThresholdingModel);
        when(clock.instant()).thenReturn(Instant.MIN, Instant.EPOCH, Instant.EPOCH);
        modelManager.getThresholdingResult(detectorId, modelId, score);

        modelManager.maintenance();

        modelManager.getThresholdingResult(detectorId, modelId, score);
        verify(checkpointDao, times(1)).getModelCheckpoint(modelId);
    }

    @Test
    public void maintenance_skipCheckpoint_whenLastCheckpointIsRecent() {
        String detectorId = "testDetectorId";
        String modelId = "testModelId";
        String checkpoint = "testCheckpoint";
        double score = 1.;
        when(checkpointDao.getModelCheckpoint(modelId)).thenReturn(Optional.of(checkpoint));
        doReturn(hybridThresholdingModel).when(gson).fromJson(checkpoint, thresholdingModelClass);
        doReturn(checkpoint).when(gson).toJson(hybridThresholdingModel);
        when(clock.instant()).thenReturn(Instant.MIN, Instant.EPOCH);
        modelManager.getThresholdingResult(detectorId, modelId, score);

        modelManager.maintenance();
        modelManager.maintenance();

        verify(checkpointDao, times(1)).putModelCheckpoint(eq(modelId), anyObject());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void maintenance_returnExpectedToListener_forRcfModel() {
        String successModelId = "testSuccessModelId";
        String failModelId = "testFailModelId";
        String successCheckpoint = "testSuccessCheckpoint";
        String failCheckpoint = "testFailCheckpoint";
        double[] point = new double[0];
        RandomCutForest forest = mock(RandomCutForest.class);
        RandomCutForest failForest = mock(RandomCutForest.class);

        doAnswer(invocation -> {
            ActionListener<Optional<String>> listener = invocation.getArgument(1);
            listener.onResponse(Optional.of(successCheckpoint));
            return null;
        }).when(checkpointDao).getModelCheckpoint(eq(successModelId), any(ActionListener.class));
        doAnswer(invocation -> {
            ActionListener<Optional<String>> listener = invocation.getArgument(1);
            listener.onResponse(Optional.of(failCheckpoint));
            return null;
        }).when(checkpointDao).getModelCheckpoint(eq(failModelId), any(ActionListener.class));
        doAnswer(invocation -> {
            ActionListener<Optional<String>> listener = invocation.getArgument(2);
            listener.onResponse(null);
            return null;
        }).when(checkpointDao).putModelCheckpoint(eq(successModelId), eq(successCheckpoint), any(ActionListener.class));
        doAnswer(invocation -> {
            ActionListener<Optional<String>> listener = invocation.getArgument(2);
            listener.onFailure(new RuntimeException());
            return null;
        }).when(checkpointDao).putModelCheckpoint(eq(failModelId), eq(failCheckpoint), any(ActionListener.class));
        when(rcfSerde.fromJson(successCheckpoint)).thenReturn(forest);
        when(rcfSerde.fromJson(failCheckpoint)).thenReturn(failForest);
        when(rcfSerde.toJson(forest)).thenReturn(successCheckpoint);
        when(rcfSerde.toJson(failForest)).thenReturn(failCheckpoint);
        when(clock.instant()).thenReturn(Instant.EPOCH);
        ActionListener<RcfResult> scoreListener = mock(ActionListener.class);
        modelManager.getRcfResult(detectorId, successModelId, point, scoreListener);
        modelManager.getRcfResult(detectorId, failModelId, point, scoreListener);

        ActionListener<Void> listener = mock(ActionListener.class);
        modelManager.maintenance(listener);

        verify(listener).onResponse(eq(null));
        verify(checkpointDao, times(1)).putModelCheckpoint(eq(successModelId), eq(successCheckpoint), any(ActionListener.class));
        verify(checkpointDao, times(1)).putModelCheckpoint(eq(failModelId), eq(failCheckpoint), any(ActionListener.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void maintenance_returnExpectedToListener_forThresholdModel() {
        String successModelId = "testSuccessModelId";
        String failModelId = "testFailModelId";
        String successCheckpoint = "testSuccessCheckpoint";
        String failCheckpoint = "testFailCheckpoint";
        double score = 1.;
        HybridThresholdingModel failThresholdModel = mock(HybridThresholdingModel.class);
        doAnswer(invocation -> {
            ActionListener<Optional<String>> listener = invocation.getArgument(1);
            listener.onResponse(Optional.of(successCheckpoint));
            return null;
        }).when(checkpointDao).getModelCheckpoint(eq(successModelId), any(ActionListener.class));
        doAnswer(invocation -> {
            ActionListener<Optional<String>> listener = invocation.getArgument(1);
            listener.onResponse(Optional.of(failCheckpoint));
            return null;
        }).when(checkpointDao).getModelCheckpoint(eq(failModelId), any(ActionListener.class));
        doAnswer(invocation -> {
            ActionListener<Optional<String>> listener = invocation.getArgument(2);
            listener.onResponse(null);
            return null;
        }).when(checkpointDao).putModelCheckpoint(eq(successModelId), eq(successCheckpoint), any(ActionListener.class));
        doAnswer(invocation -> {
            ActionListener<Optional<String>> listener = invocation.getArgument(2);
            listener.onFailure(new RuntimeException());
            return null;
        }).when(checkpointDao).putModelCheckpoint(eq(failModelId), eq(failCheckpoint), any(ActionListener.class));
        doReturn(hybridThresholdingModel).when(gson).fromJson(successCheckpoint, thresholdingModelClass);
        doReturn(failThresholdModel).when(gson).fromJson(failCheckpoint, thresholdingModelClass);
        doReturn(successCheckpoint).when(gson).toJson(hybridThresholdingModel);
        doThrow(new RuntimeException()).when(gson).toJson(failThresholdModel);
        when(clock.instant()).thenReturn(Instant.EPOCH);
        ActionListener<ThresholdingResult> scoreListener = mock(ActionListener.class);
        modelManager.getThresholdingResult(detectorId, successModelId, score, scoreListener);
        modelManager.getThresholdingResult(detectorId, failModelId, score, scoreListener);

        ActionListener<Void> listener = mock(ActionListener.class);
        modelManager.maintenance(listener);

        verify(listener).onResponse(eq(null));
        verify(checkpointDao, times(1)).putModelCheckpoint(eq(successModelId), eq(successCheckpoint), any(ActionListener.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void maintenance_returnExpectedToListener_stopModel() {
        double[] point = new double[0];
        RandomCutForest forest = mock(RandomCutForest.class);

        doAnswer(invocation -> {
            ActionListener<Optional<String>> listener = invocation.getArgument(1);
            listener.onResponse(Optional.of(checkpoint));
            return null;
        }).when(checkpointDao).getModelCheckpoint(eq(rcfModelId), any(ActionListener.class));
        doAnswer(invocation -> {
            ActionListener<Optional<String>> listener = invocation.getArgument(2);
            listener.onResponse(null);
            return null;
        }).when(checkpointDao).putModelCheckpoint(eq(rcfModelId), eq(checkpoint), any(ActionListener.class));
        when(rcfSerde.fromJson(checkpoint)).thenReturn(forest);
        when(rcfSerde.toJson(forest)).thenReturn(checkpoint);
        when(clock.instant()).thenReturn(Instant.EPOCH, Instant.EPOCH, Instant.EPOCH.plus(modelTtl.plusSeconds(1)));
        ActionListener<RcfResult> scoreListener = mock(ActionListener.class);
        modelManager.getRcfResult(detectorId, rcfModelId, point, scoreListener);

        ActionListener<Void> listener = mock(ActionListener.class);
        modelManager.maintenance(listener);
        verify(listener).onResponse(eq(null));

        modelManager.getRcfResult(detectorId, rcfModelId, point, scoreListener);
        verify(checkpointDao, times(2)).getModelCheckpoint(eq(rcfModelId), any(ActionListener.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void maintenance_returnExpectedToListener_doNothing() {
        double[] point = new double[0];
        RandomCutForest forest = mock(RandomCutForest.class);

        doAnswer(invocation -> {
            ActionListener<Optional<String>> listener = invocation.getArgument(1);
            listener.onResponse(Optional.of(checkpoint));
            return null;
        }).when(checkpointDao).getModelCheckpoint(eq(rcfModelId), any(ActionListener.class));
        doAnswer(invocation -> {
            ActionListener<Optional<String>> listener = invocation.getArgument(2);
            listener.onResponse(null);
            return null;
        }).when(checkpointDao).putModelCheckpoint(eq(rcfModelId), eq(checkpoint), any(ActionListener.class));
        when(rcfSerde.fromJson(checkpoint)).thenReturn(forest);
        when(rcfSerde.toJson(forest)).thenReturn(checkpoint);
        when(clock.instant()).thenReturn(Instant.MIN);
        ActionListener<RcfResult> scoreListener = mock(ActionListener.class);
        modelManager.getRcfResult(detectorId, rcfModelId, point, scoreListener);
        ActionListener<Void> listener = mock(ActionListener.class);
        modelManager.maintenance(listener);
        verify(listener).onResponse(eq(null));

        listener = mock(ActionListener.class);
        modelManager.maintenance(listener);
        verify(listener).onResponse(eq(null));

        modelManager.getRcfResult(detectorId, rcfModelId, point, scoreListener);
        verify(checkpointDao, times(1)).getModelCheckpoint(eq(rcfModelId), any(ActionListener.class));
    }

    @Test
    public void getPreviewResults_returnNoAnomalies_forNoAnomalies() {
        int numPoints = 1000;
        double[][] points = Stream.generate(() -> new double[] { 0 }).limit(numPoints).toArray(double[][]::new);

        List<ThresholdingResult> results = modelManager.getPreviewResults(points);

        assertEquals(numPoints, results.size());
        assertTrue(results.stream().noneMatch(r -> r.getGrade() > 0));
    }

    @Test
    public void getPreviewResults_returnAnomalies_forLastAnomaly() {
        int numPoints = 1000;
        double[][] points = Stream.generate(() -> new double[] { 0 }).limit(numPoints).toArray(double[][]::new);
        points[points.length - 1] = new double[] { 1. };

        List<ThresholdingResult> results = modelManager.getPreviewResults(points);

        assertEquals(numPoints, results.size());
        assertTrue(results.stream().limit(numPoints - 1).noneMatch(r -> r.getGrade() > 0));
        assertTrue(results.get(numPoints - 1).getGrade() > 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void getPreviewResults_throwIllegalArgument_forInvalidInput() {
        modelManager.getPreviewResults(new double[0][0]);
    }
}
