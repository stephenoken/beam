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
package org.apache.beam.fn.harness;

import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkArgument;
import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkNotNull;
import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkState;

import com.google.auto.service.AutoService;
import com.google.auto.value.AutoValue;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.beam.fn.harness.PTransformRunnerFactory.ProgressRequestCallback;
import org.apache.beam.fn.harness.control.BundleSplitListener;
import org.apache.beam.fn.harness.data.BeamFnDataClient;
import org.apache.beam.fn.harness.data.BeamFnTimerClient;
import org.apache.beam.fn.harness.data.BeamFnTimerClient.TimerHandler;
import org.apache.beam.fn.harness.data.PCollectionConsumerRegistry;
import org.apache.beam.fn.harness.data.PTransformFunctionRegistry;
import org.apache.beam.fn.harness.state.BeamFnStateClient;
import org.apache.beam.fn.harness.state.FnApiStateAccessor;
import org.apache.beam.fn.harness.state.SideInputSpec;
import org.apache.beam.model.fnexecution.v1.BeamFnApi.BundleApplication;
import org.apache.beam.model.fnexecution.v1.BeamFnApi.DelayedBundleApplication;
import org.apache.beam.model.pipeline.v1.MetricsApi.MonitoringInfo;
import org.apache.beam.model.pipeline.v1.RunnerApi;
import org.apache.beam.model.pipeline.v1.RunnerApi.PCollection;
import org.apache.beam.model.pipeline.v1.RunnerApi.PTransform;
import org.apache.beam.model.pipeline.v1.RunnerApi.ParDoPayload;
import org.apache.beam.model.pipeline.v1.RunnerApi.TimerFamilySpec;
import org.apache.beam.runners.core.DoFnRunner;
import org.apache.beam.runners.core.LateDataUtils;
import org.apache.beam.runners.core.construction.PCollectionViewTranslation;
import org.apache.beam.runners.core.construction.PTransformTranslation;
import org.apache.beam.runners.core.construction.ParDoTranslation;
import org.apache.beam.runners.core.construction.RehydratedComponents;
import org.apache.beam.runners.core.construction.Timer;
import org.apache.beam.runners.core.metrics.MonitoringInfoConstants;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.DoubleCoder;
import org.apache.beam.sdk.coders.IterableCoder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.fn.data.FnDataReceiver;
import org.apache.beam.sdk.fn.data.LogicalEndpoint;
import org.apache.beam.sdk.fn.splittabledofn.RestrictionTrackers;
import org.apache.beam.sdk.fn.splittabledofn.RestrictionTrackers.ClaimObserver;
import org.apache.beam.sdk.fn.splittabledofn.WatermarkEstimators;
import org.apache.beam.sdk.function.ThrowingRunnable;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.schemas.SchemaCoder;
import org.apache.beam.sdk.state.ReadableState;
import org.apache.beam.sdk.state.State;
import org.apache.beam.sdk.state.StateSpec;
import org.apache.beam.sdk.state.TimeDomain;
import org.apache.beam.sdk.state.TimerMap;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.DoFn.BundleFinalizer;
import org.apache.beam.sdk.transforms.DoFn.MultiOutputReceiver;
import org.apache.beam.sdk.transforms.DoFn.OutputReceiver;
import org.apache.beam.sdk.transforms.DoFnOutputReceivers;
import org.apache.beam.sdk.transforms.DoFnSchemaInformation;
import org.apache.beam.sdk.transforms.Materializations;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.reflect.DoFnInvoker;
import org.apache.beam.sdk.transforms.reflect.DoFnInvoker.BaseArgumentProvider;
import org.apache.beam.sdk.transforms.reflect.DoFnInvoker.DelegatingArgumentProvider;
import org.apache.beam.sdk.transforms.reflect.DoFnInvokers;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.StateDeclaration;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.TimerDeclaration;
import org.apache.beam.sdk.transforms.reflect.DoFnSignatures;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker.HasProgress;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker.Progress;
import org.apache.beam.sdk.transforms.splittabledofn.SplitResult;
import org.apache.beam.sdk.transforms.splittabledofn.TimestampObservingWatermarkEstimator;
import org.apache.beam.sdk.transforms.splittabledofn.WatermarkEstimator;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.GlobalWindow;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.apache.beam.sdk.util.UserCodeException;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.util.WindowedValue.WindowedValueCoder;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.WindowingStrategy;
import org.apache.beam.vendor.grpc.v1p26p0.com.google.protobuf.ByteString;
import org.apache.beam.vendor.grpc.v1p26p0.com.google.protobuf.util.Durations;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableList;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableListMultimap;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableMap;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.Iterables;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ListMultimap;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.Maps;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.Sets;
import org.joda.time.DateTimeUtils;
import org.joda.time.Duration;
import org.joda.time.Instant;

/**
 * A {@link DoFnRunner} specific to integrating with the Fn Api. This is to remove the layers of
 * abstraction caused by StateInternals/TimerInternals since they model state and timer concepts
 * differently.
 */
public class FnApiDoFnRunner<InputT, RestrictionT, PositionT, WatermarkEstimatorStateT, OutputT> {
  /** A registrar which provides a factory to handle Java {@link DoFn}s. */
  @AutoService(PTransformRunnerFactory.Registrar.class)
  public static class Registrar implements PTransformRunnerFactory.Registrar {
    @Override
    public Map<String, PTransformRunnerFactory> getPTransformRunnerFactories() {
      Factory factory = new Factory();
      return ImmutableMap.<String, PTransformRunnerFactory>builder()
          .put(PTransformTranslation.PAR_DO_TRANSFORM_URN, factory)
          .put(PTransformTranslation.SPLITTABLE_PAIR_WITH_RESTRICTION_URN, factory)
          .put(PTransformTranslation.SPLITTABLE_SPLIT_RESTRICTION_URN, factory)
          .put(PTransformTranslation.SPLITTABLE_SPLIT_AND_SIZE_RESTRICTIONS_URN, factory)
          .put(PTransformTranslation.SPLITTABLE_PROCESS_ELEMENTS_URN, factory)
          .put(
              PTransformTranslation.SPLITTABLE_PROCESS_SIZED_ELEMENTS_AND_RESTRICTIONS_URN, factory)
          .build();
    }
  }

  static class Factory<InputT, RestrictionT, PositionT, WatermarkEstimatorStateT, OutputT>
      implements PTransformRunnerFactory<
          FnApiDoFnRunner<InputT, RestrictionT, PositionT, WatermarkEstimatorStateT, OutputT>> {

    @Override
    public final FnApiDoFnRunner<InputT, RestrictionT, PositionT, WatermarkEstimatorStateT, OutputT>
        createRunnerForPTransform(
            PipelineOptions pipelineOptions,
            BeamFnDataClient beamFnDataClient,
            BeamFnStateClient beamFnStateClient,
            BeamFnTimerClient beamFnTimerClient,
            String pTransformId,
            PTransform pTransform,
            Supplier<String> processBundleInstructionId,
            Map<String, PCollection> pCollections,
            Map<String, RunnerApi.Coder> coders,
            Map<String, RunnerApi.WindowingStrategy> windowingStrategies,
            PCollectionConsumerRegistry pCollectionConsumerRegistry,
            PTransformFunctionRegistry startFunctionRegistry,
            PTransformFunctionRegistry finishFunctionRegistry,
            Consumer<ThrowingRunnable> tearDownFunctions,
            Consumer<ProgressRequestCallback> addProgressRequestCallback,
            BundleSplitListener splitListener,
            BundleFinalizer bundleFinalizer) {

      FnApiDoFnRunner<InputT, RestrictionT, PositionT, WatermarkEstimatorStateT, OutputT> runner =
          new FnApiDoFnRunner<>(
              pipelineOptions,
              beamFnStateClient,
              beamFnTimerClient,
              pTransformId,
              pTransform,
              processBundleInstructionId,
              pCollections,
              coders,
              windowingStrategies,
              pCollectionConsumerRegistry,
              addProgressRequestCallback,
              splitListener,
              bundleFinalizer);

      // Register the appropriate handlers.
      startFunctionRegistry.register(pTransformId, runner::startBundle);
      String mainInput;
      try {
        mainInput = ParDoTranslation.getMainInputName(pTransform);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      final FnDataReceiver<WindowedValue> mainInputConsumer;
      switch (pTransform.getSpec().getUrn()) {
        case PTransformTranslation.PAR_DO_TRANSFORM_URN:
          mainInputConsumer = runner::processElementForParDo;
          break;
        case PTransformTranslation.SPLITTABLE_PAIR_WITH_RESTRICTION_URN:
          mainInputConsumer = runner::processElementForPairWithRestriction;
          break;
        case PTransformTranslation.SPLITTABLE_SPLIT_RESTRICTION_URN:
        case PTransformTranslation.SPLITTABLE_SPLIT_AND_SIZE_RESTRICTIONS_URN:
          mainInputConsumer = runner::processElementForSplitRestriction;
          break;
        case PTransformTranslation.SPLITTABLE_PROCESS_ELEMENTS_URN:
          mainInputConsumer =
              runner.new SplittableFnDataReceiver() {
                @Override
                public void accept(WindowedValue input) throws Exception {
                  runner.processElementForElementAndRestriction(input);
                }
              };
          break;
        case PTransformTranslation.SPLITTABLE_PROCESS_SIZED_ELEMENTS_AND_RESTRICTIONS_URN:
          mainInputConsumer =
              runner.new SplittableFnDataReceiver() {
                @Override
                public void accept(WindowedValue input) throws Exception {
                  runner.processElementForSizedElementAndRestriction(input);
                }
              };
          break;
        default:
          throw new IllegalStateException("Unknown urn: " + pTransform.getSpec().getUrn());
      }
      pCollectionConsumerRegistry.register(
          pTransform.getInputsOrThrow(mainInput), pTransformId, (FnDataReceiver) mainInputConsumer);
      finishFunctionRegistry.register(pTransformId, runner::finishBundle);
      tearDownFunctions.accept(runner::tearDown);
      return runner;
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////

  private final PipelineOptions pipelineOptions;
  private final BeamFnStateClient beamFnStateClient;
  private final String pTransformId;
  private final PTransform pTransform;
  private final Supplier<String> processBundleInstructionId;
  private final RehydratedComponents rehydratedComponents;
  private final DoFn<InputT, OutputT> doFn;
  private final DoFnSignature doFnSignature;
  private final TupleTag<OutputT> mainOutputTag;
  private final Coder<?> inputCoder;
  private final SchemaCoder<InputT> schemaCoder;
  private final Coder<?> keyCoder;
  private final SchemaCoder<OutputT> mainOutputSchemaCoder;
  private final Coder<? extends BoundedWindow> windowCoder;
  private final WindowingStrategy<InputT, ?> windowingStrategy;
  private final Map<TupleTag<?>, SideInputSpec> tagToSideInputSpecMap;
  private final Map<TupleTag<?>, Coder<?>> outputCoders;
  private final BeamFnTimerClient beamFnTimerClient;
  private final Map<String, KV<TimeDomain, Coder<Timer<Object>>>> timerFamilyInfos;
  private final ParDoPayload parDoPayload;
  private final ListMultimap<String, FnDataReceiver<WindowedValue<?>>> localNameToConsumer;
  private final BundleSplitListener splitListener;
  private final BundleFinalizer bundleFinalizer;
  private final Collection<FnDataReceiver<WindowedValue<OutputT>>> mainOutputConsumers;

  private final String mainInputId;
  private FnApiStateAccessor<?> stateAccessor;
  private Map<String, BeamFnTimerClient.TimerHandler<?>> timerHandlers;
  private final DoFnInvoker<InputT, OutputT> doFnInvoker;
  private final StartBundleArgumentProvider startBundleArgumentProvider;
  private final ProcessBundleContext processContext;
  private final OnTimerContext onTimerContext;
  private final FinishBundleArgumentProvider finishBundleArgumentProvider;

  /**
   * Used to guarantee a consistent view of this {@link FnApiDoFnRunner} while setting up for {@link
   * DoFnInvoker#invokeProcessElement} since {@link #trySplitForElementAndRestriction} may access
   * internal {@link FnApiDoFnRunner} state concurrently.
   */
  private final Object splitLock = new Object();

  /**
   * Only set for {@link PTransformTranslation#SPLITTABLE_PROCESS_ELEMENTS_URN} and {@link
   * PTransformTranslation#SPLITTABLE_PROCESS_SIZED_ELEMENTS_AND_RESTRICTIONS_URN} transforms. Can
   * only be invoked from within {@code processElement...} methods.
   */
  private final BiFunction<SplitResult<RestrictionT>, WatermarkEstimatorStateT, WindowedSplitResult>
      convertSplitResultToWindowedSplitResult;

  private final DoFnSchemaInformation doFnSchemaInformation;
  private final Map<String, PCollectionView<?>> sideInputMapping;

  // The member variables below are only valid for the lifetime of certain methods.
  /** Only valid during {@code processElement...} methods, null otherwise. */
  private WindowedValue<InputT> currentElement;

  /**
   * Only valid during {@link #processElementForPairWithRestriction}, {@link
   * #processElementForSplitRestriction}, {@link #processElementForElementAndRestriction} and {@link
   * #processElementForSizedElementAndRestriction}, null otherwise.
   */
  private RestrictionT currentRestriction;

  /**
   * Only valid during {@link #processElementForSplitRestriction}, {@link
   * #processElementForElementAndRestriction} and {@link
   * #processElementForSizedElementAndRestriction}, null otherwise.
   */
  private WatermarkEstimatorStateT currentWatermarkEstimatorState;

  /**
   * Only valid during {@link #processElementForElementAndRestriction} and {@link
   * #processElementForSizedElementAndRestriction}, null otherwise.
   */
  private WatermarkEstimators.WatermarkAndStateObserver<WatermarkEstimatorStateT>
      currentWatermarkEstimator;

  /**
   * Only valid during {@code processElement...} and {@link #processTimer} methods, null otherwise.
   */
  private BoundedWindow currentWindow;

  /**
   * Only valid during {@link #processElementForElementAndRestriction} and {@link
   * #processElementForSizedElementAndRestriction}, null otherwise.
   */
  private RestrictionTracker<RestrictionT, PositionT> currentTracker;

  /** Only valid during {@link #processTimer}, null otherwise. */
  private Timer<?> currentTimer;

  /** Only valid during {@link #processTimer}, null otherwise. */
  private TimeDomain currentTimeDomain;

  FnApiDoFnRunner(
      PipelineOptions pipelineOptions,
      BeamFnStateClient beamFnStateClient,
      BeamFnTimerClient beamFnTimerClient,
      String pTransformId,
      PTransform pTransform,
      Supplier<String> processBundleInstructionId,
      Map<String, PCollection> pCollections,
      Map<String, RunnerApi.Coder> coders,
      Map<String, RunnerApi.WindowingStrategy> windowingStrategies,
      PCollectionConsumerRegistry pCollectionConsumerRegistry,
      Consumer<ProgressRequestCallback> addProgressRequestCallback,
      BundleSplitListener splitListener,
      BundleFinalizer bundleFinalizer) {
    this.pipelineOptions = pipelineOptions;
    this.beamFnStateClient = beamFnStateClient;
    this.beamFnTimerClient = beamFnTimerClient;
    this.pTransformId = pTransformId;
    this.pTransform = pTransform;
    this.processBundleInstructionId = processBundleInstructionId;
    ImmutableMap.Builder<TupleTag<?>, SideInputSpec> tagToSideInputSpecMapBuilder =
        ImmutableMap.builder();
    try {
      rehydratedComponents =
          RehydratedComponents.forComponents(
                  RunnerApi.Components.newBuilder()
                      .putAllCoders(coders)
                      .putAllPcollections(pCollections)
                      .putAllWindowingStrategies(windowingStrategies)
                      .build())
              .withPipeline(Pipeline.create());
      parDoPayload = ParDoPayload.parseFrom(pTransform.getSpec().getPayload());
      doFn = (DoFn) ParDoTranslation.getDoFn(parDoPayload);
      doFnSignature = DoFnSignatures.signatureForDoFn(doFn);
      switch (pTransform.getSpec().getUrn()) {
        case PTransformTranslation.SPLITTABLE_PROCESS_ELEMENTS_URN:
        case PTransformTranslation.SPLITTABLE_PROCESS_SIZED_ELEMENTS_AND_RESTRICTIONS_URN:
        case PTransformTranslation.PAR_DO_TRANSFORM_URN:
          mainOutputTag = (TupleTag) ParDoTranslation.getMainOutputTag(parDoPayload);
          break;
        case PTransformTranslation.SPLITTABLE_PAIR_WITH_RESTRICTION_URN:
        case PTransformTranslation.SPLITTABLE_SPLIT_AND_SIZE_RESTRICTIONS_URN:
        case PTransformTranslation.SPLITTABLE_SPLIT_RESTRICTION_URN:
          mainOutputTag =
              new TupleTag(Iterables.getOnlyElement(pTransform.getOutputsMap().keySet()));
          break;
        default:
          throw new IllegalStateException(
              String.format("Unknown urn: %s", pTransform.getSpec().getUrn()));
      }
      String mainInputTag =
          Iterables.getOnlyElement(
              Sets.difference(
                  pTransform.getInputsMap().keySet(), parDoPayload.getSideInputsMap().keySet()));
      PCollection mainInput = pCollections.get(pTransform.getInputsOrThrow(mainInputTag));
      Coder<?> maybeWindowedValueInputCoder = rehydratedComponents.getCoder(mainInput.getCoderId());
      // TODO: Stop passing windowed value coders within PCollections.
      if (maybeWindowedValueInputCoder instanceof WindowedValue.WindowedValueCoder) {
        inputCoder = ((WindowedValueCoder) maybeWindowedValueInputCoder).getValueCoder();
      } else {
        inputCoder = maybeWindowedValueInputCoder;
      }
      if (inputCoder instanceof KvCoder) {
        this.keyCoder = ((KvCoder) inputCoder).getKeyCoder();
      } else {
        this.keyCoder = null;
      }
      if (inputCoder instanceof SchemaCoder) {
        this.schemaCoder = ((SchemaCoder<InputT>) inputCoder);
      } else {
        this.schemaCoder = null;
      }

      windowingStrategy =
          (WindowingStrategy)
              rehydratedComponents.getWindowingStrategy(mainInput.getWindowingStrategyId());
      windowCoder = windowingStrategy.getWindowFn().windowCoder();

      outputCoders = Maps.newHashMap();
      for (Map.Entry<String, String> entry : pTransform.getOutputsMap().entrySet()) {
        TupleTag<?> outputTag = new TupleTag<>(entry.getKey());
        RunnerApi.PCollection outputPCollection = pCollections.get(entry.getValue());
        Coder<?> outputCoder = rehydratedComponents.getCoder(outputPCollection.getCoderId());
        if (outputCoder instanceof WindowedValueCoder) {
          outputCoder = ((WindowedValueCoder) outputCoder).getValueCoder();
        }
        outputCoders.put(outputTag, outputCoder);
      }
      Coder<OutputT> outputCoder = (Coder<OutputT>) outputCoders.get(mainOutputTag);
      mainOutputSchemaCoder =
          (outputCoder instanceof SchemaCoder) ? (SchemaCoder<OutputT>) outputCoder : null;

      // Build the map from tag id to side input specification
      for (Map.Entry<String, RunnerApi.SideInput> entry :
          parDoPayload.getSideInputsMap().entrySet()) {
        String sideInputTag = entry.getKey();
        RunnerApi.SideInput sideInput = entry.getValue();
        checkArgument(
            Materializations.MULTIMAP_MATERIALIZATION_URN.equals(
                sideInput.getAccessPattern().getUrn()),
            "This SDK is only capable of dealing with %s materializations "
                + "but was asked to handle %s for PCollectionView with tag %s.",
            Materializations.MULTIMAP_MATERIALIZATION_URN,
            sideInput.getAccessPattern().getUrn(),
            sideInputTag);

        PCollection sideInputPCollection =
            pCollections.get(pTransform.getInputsOrThrow(sideInputTag));
        WindowingStrategy sideInputWindowingStrategy =
            rehydratedComponents.getWindowingStrategy(
                sideInputPCollection.getWindowingStrategyId());
        tagToSideInputSpecMapBuilder.put(
            new TupleTag<>(entry.getKey()),
            SideInputSpec.create(
                rehydratedComponents.getCoder(sideInputPCollection.getCoderId()),
                sideInputWindowingStrategy.getWindowFn().windowCoder(),
                PCollectionViewTranslation.viewFnFromProto(entry.getValue().getViewFn()),
                PCollectionViewTranslation.windowMappingFnFromProto(
                    entry.getValue().getWindowMappingFn())));
      }

      ImmutableMap.Builder<String, KV<TimeDomain, Coder<Timer<Object>>>> timerFamilyInfosBuilder =
          ImmutableMap.builder();
      // Extract out relevant TimerFamilySpec information in preparation for execution.
      for (Map.Entry<String, TimerFamilySpec> entry :
          parDoPayload.getTimerFamilySpecsMap().entrySet()) {
        String timerFamilyId = entry.getKey();
        TimeDomain timeDomain =
            DoFnSignatures.getTimerSpecOrThrow(
                    doFnSignature.timerDeclarations().get(timerFamilyId), doFn)
                .getTimeDomain();
        Coder<Timer<Object>> timerCoder =
            (Coder) rehydratedComponents.getCoder(entry.getValue().getTimerFamilyCoderId());
        timerFamilyInfosBuilder.put(timerFamilyId, KV.of(timeDomain, timerCoder));
      }
      timerFamilyInfos = timerFamilyInfosBuilder.build();

    } catch (IOException exn) {
      throw new IllegalArgumentException("Malformed ParDoPayload", exn);
    }

    ImmutableListMultimap.Builder<String, FnDataReceiver<WindowedValue<?>>>
        localNameToConsumerBuilder = ImmutableListMultimap.builder();
    for (Map.Entry<String, String> entry : pTransform.getOutputsMap().entrySet()) {
      localNameToConsumerBuilder.putAll(
          entry.getKey(), pCollectionConsumerRegistry.getMultiplexingConsumer(entry.getValue()));
    }
    localNameToConsumer = localNameToConsumerBuilder.build();
    tagToSideInputSpecMap = tagToSideInputSpecMapBuilder.build();
    this.splitListener = splitListener;
    this.bundleFinalizer = bundleFinalizer;

    try {
      this.mainInputId = ParDoTranslation.getMainInputName(pTransform);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    this.mainOutputConsumers =
        (Collection<FnDataReceiver<WindowedValue<OutputT>>>)
            (Collection) localNameToConsumer.get(mainOutputTag.getId());
    this.doFnSchemaInformation = ParDoTranslation.getSchemaInformation(parDoPayload);
    this.sideInputMapping = ParDoTranslation.getSideInputMapping(parDoPayload);
    this.doFnInvoker = DoFnInvokers.invokerFor(doFn);
    this.doFnInvoker.invokeSetup();

    this.startBundleArgumentProvider = new StartBundleArgumentProvider();
    switch (pTransform.getSpec().getUrn()) {
      case PTransformTranslation.SPLITTABLE_SPLIT_RESTRICTION_URN:
        // OutputT == RestrictionT
        this.processContext =
            new ProcessBundleContext() {
              @Override
              public void outputWithTimestamp(OutputT output, Instant timestamp) {
                outputTo(
                    mainOutputConsumers,
                    (WindowedValue<OutputT>)
                        WindowedValue.of(
                            KV.of(
                                currentElement.getValue(),
                                KV.of(output, currentWatermarkEstimatorState)),
                            timestamp,
                            currentWindow,
                            currentElement.getPane()));
              }
            };
        break;
      case PTransformTranslation.SPLITTABLE_SPLIT_AND_SIZE_RESTRICTIONS_URN:
        // OutputT == RestrictionT
        this.processContext =
            new ProcessBundleContext() {
              @Override
              public void outputWithTimestamp(OutputT output, Instant timestamp) {
                double size =
                    doFnInvoker.invokeGetSize(
                        new DelegatingArgumentProvider<InputT, OutputT>(
                            this,
                            PTransformTranslation.SPLITTABLE_SPLIT_AND_SIZE_RESTRICTIONS_URN
                                + "/GetSize") {
                          @Override
                          public Object restriction() {
                            return output;
                          }

                          @Override
                          public Instant timestamp(DoFn<InputT, OutputT> doFn) {
                            return timestamp;
                          }

                          @Override
                          public RestrictionTracker<?, ?> restrictionTracker() {
                            return doFnInvoker.invokeNewTracker(this);
                          }
                        });

                outputTo(
                    mainOutputConsumers,
                    (WindowedValue<OutputT>)
                        WindowedValue.of(
                            KV.of(
                                KV.of(
                                    currentElement.getValue(),
                                    KV.of(output, currentWatermarkEstimatorState)),
                                size),
                            timestamp,
                            currentWindow,
                            currentElement.getPane()));
              }
            };
        break;
      case PTransformTranslation.SPLITTABLE_PAIR_WITH_RESTRICTION_URN:
      case PTransformTranslation.SPLITTABLE_PROCESS_ELEMENTS_URN:
      case PTransformTranslation.SPLITTABLE_PROCESS_SIZED_ELEMENTS_AND_RESTRICTIONS_URN:
      case PTransformTranslation.PAR_DO_TRANSFORM_URN:
        this.processContext = new ProcessBundleContext();
        break;
      default:
        throw new IllegalStateException(
            String.format("Unknown URN %s", pTransform.getSpec().getUrn()));
    }
    this.onTimerContext = new OnTimerContext();
    this.finishBundleArgumentProvider = new FinishBundleArgumentProvider();

    switch (pTransform.getSpec().getUrn()) {
      case PTransformTranslation.SPLITTABLE_PROCESS_ELEMENTS_URN:
        this.convertSplitResultToWindowedSplitResult =
            (splitResult, watermarkEstimatorState) ->
                WindowedSplitResult.forRoots(
                    currentElement.withValue(
                        KV.of(
                            currentElement.getValue(),
                            KV.of(splitResult.getPrimary(), currentWatermarkEstimatorState))),
                    currentElement.withValue(
                        KV.of(
                            currentElement.getValue(),
                            KV.of(splitResult.getResidual(), watermarkEstimatorState))));
        break;
      case PTransformTranslation.SPLITTABLE_PROCESS_SIZED_ELEMENTS_AND_RESTRICTIONS_URN:
        this.convertSplitResultToWindowedSplitResult =
            (splitResult, watermarkEstimatorState) -> {
              double primarySize =
                  doFnInvoker.invokeGetSize(
                      new DelegatingArgumentProvider<InputT, OutputT>(
                          processContext,
                          PTransformTranslation
                                  .SPLITTABLE_PROCESS_SIZED_ELEMENTS_AND_RESTRICTIONS_URN
                              + "/GetPrimarySize") {
                        @Override
                        public Object restriction() {
                          return splitResult.getPrimary();
                        }

                        @Override
                        public RestrictionTracker<?, ?> restrictionTracker() {
                          return doFnInvoker.invokeNewTracker(this);
                        }
                      });
              double residualSize =
                  doFnInvoker.invokeGetSize(
                      new DelegatingArgumentProvider<InputT, OutputT>(
                          processContext,
                          PTransformTranslation
                                  .SPLITTABLE_PROCESS_SIZED_ELEMENTS_AND_RESTRICTIONS_URN
                              + "/GetResidualSize") {
                        @Override
                        public Object restriction() {
                          return splitResult.getResidual();
                        }

                        @Override
                        public RestrictionTracker<?, ?> restrictionTracker() {
                          return doFnInvoker.invokeNewTracker(this);
                        }
                      });
              return WindowedSplitResult.forRoots(
                  currentElement.withValue(
                      KV.of(
                          KV.of(
                              currentElement.getValue(),
                              KV.of(splitResult.getPrimary(), currentWatermarkEstimatorState)),
                          primarySize)),
                  currentElement.withValue(
                      KV.of(
                          KV.of(
                              currentElement.getValue(),
                              KV.of(splitResult.getResidual(), watermarkEstimatorState)),
                          residualSize)));
            };
        break;
      default:
        this.convertSplitResultToWindowedSplitResult =
            (splitResult, watermarkEstimatorStateT) -> {
              throw new IllegalStateException(
                  String.format(
                      "Unimplemented split conversion handler for %s.",
                      pTransform.getSpec().getUrn()));
            };
    }

    switch (pTransform.getSpec().getUrn()) {
      case PTransformTranslation.SPLITTABLE_PROCESS_ELEMENTS_URN:
      case PTransformTranslation.SPLITTABLE_PROCESS_SIZED_ELEMENTS_AND_RESTRICTIONS_URN:
        addProgressRequestCallback.accept(
            new ProgressRequestCallback() {
              @Override
              public List<MonitoringInfo> getMonitoringInfos() throws Exception {
                Progress progress = getProgress();
                if (progress == null) {
                  return Collections.emptyList();
                }
                MonitoringInfo.Builder completedBuilder = MonitoringInfo.newBuilder();
                completedBuilder.setUrn(MonitoringInfoConstants.Urns.WORK_COMPLETED);
                completedBuilder.setType(MonitoringInfoConstants.TypeUrns.PROGRESS_TYPE);
                completedBuilder.putLabels(MonitoringInfoConstants.Labels.PTRANSFORM, pTransformId);
                completedBuilder.setPayload(encodeProgress(progress.getWorkCompleted()));
                MonitoringInfo.Builder remainingBuilder = MonitoringInfo.newBuilder();
                remainingBuilder.setUrn(MonitoringInfoConstants.Urns.WORK_REMAINING);
                remainingBuilder.setType(MonitoringInfoConstants.TypeUrns.PROGRESS_TYPE);
                remainingBuilder.putLabels(MonitoringInfoConstants.Labels.PTRANSFORM, pTransformId);
                remainingBuilder.setPayload(encodeProgress(progress.getWorkRemaining()));
                return ImmutableList.of(completedBuilder.build(), remainingBuilder.build());
              }

              private ByteString encodeProgress(double value) throws IOException {
                ByteString.Output output = ByteString.newOutput();
                IterableCoder.of(DoubleCoder.of()).encode(Arrays.asList(value), output);
                return output.toByteString();
              }
            });
        break;
      default:
        // no-op

    }
  }

  private void startBundle() {
    this.stateAccessor =
        new FnApiStateAccessor(
            pipelineOptions,
            pTransformId,
            processBundleInstructionId,
            tagToSideInputSpecMap,
            beamFnStateClient,
            keyCoder,
            (Coder<BoundedWindow>) windowCoder,
            () -> {
              if (currentElement != null) {
                checkState(
                    currentElement.getValue() instanceof KV,
                    "Accessing state in unkeyed context. Current element is not a KV: %s.",
                    currentElement.getValue());
                return ((KV) currentElement.getValue()).getKey();
              } else if (currentTimer != null) {
                return currentTimer.getUserKey();
              }
              return null;
            },
            () -> currentWindow);

    // Register as a consumer for each timer.
    timerHandlers = new HashMap<>();
    for (Map.Entry<String, KV<TimeDomain, Coder<Timer<Object>>>> timerFamilyInfo :
        timerFamilyInfos.entrySet()) {
      String localName = timerFamilyInfo.getKey();
      TimeDomain timeDomain = timerFamilyInfo.getValue().getKey();
      Coder<Timer<Object>> timerCoder = timerFamilyInfo.getValue().getValue();
      timerHandlers.put(
          localName,
          beamFnTimerClient.register(
              LogicalEndpoint.timer(processBundleInstructionId.get(), pTransformId, localName),
              timerCoder,
              (FnDataReceiver<Timer<Object>>) timer -> processTimer(localName, timeDomain, timer)));
    }

    doFnInvoker.invokeStartBundle(startBundleArgumentProvider);
  }

  private void processElementForParDo(WindowedValue<InputT> elem) {
    currentElement = elem;
    try {
      Iterator<BoundedWindow> windowIterator =
          (Iterator<BoundedWindow>) elem.getWindows().iterator();
      while (windowIterator.hasNext()) {
        currentWindow = windowIterator.next();
        doFnInvoker.invokeProcessElement(processContext);
      }
    } finally {
      currentElement = null;
      currentWindow = null;
    }
  }

  private void processElementForPairWithRestriction(WindowedValue<InputT> elem) {
    currentElement = elem;
    try {
      Iterator<BoundedWindow> windowIterator =
          (Iterator<BoundedWindow>) elem.getWindows().iterator();
      while (windowIterator.hasNext()) {
        currentWindow = windowIterator.next();
        currentRestriction = doFnInvoker.invokeGetInitialRestriction(processContext);
        outputTo(
            mainOutputConsumers,
            (WindowedValue)
                elem.withValue(
                    KV.of(
                        elem.getValue(),
                        KV.of(
                            currentRestriction,
                            doFnInvoker.invokeGetInitialWatermarkEstimatorState(processContext)))));
      }
    } finally {
      currentElement = null;
      currentWindow = null;
      currentRestriction = null;
    }
  }

  private void processElementForSplitRestriction(
      WindowedValue<KV<InputT, KV<RestrictionT, WatermarkEstimatorStateT>>> elem) {
    currentElement = elem.withValue(elem.getValue().getKey());
    currentRestriction = elem.getValue().getValue().getKey();
    currentWatermarkEstimatorState = elem.getValue().getValue().getValue();
    try {
      Iterator<BoundedWindow> windowIterator =
          (Iterator<BoundedWindow>) elem.getWindows().iterator();
      while (windowIterator.hasNext()) {
        currentWindow = windowIterator.next();
        doFnInvoker.invokeSplitRestriction(processContext);
      }
    } finally {
      currentElement = null;
      currentRestriction = null;
      currentWatermarkEstimatorState = null;
      currentWindow = null;
    }
  }

  /** Internal class to hold the primary and residual roots when converted to an input element. */
  @AutoValue
  abstract static class WindowedSplitResult {
    public static WindowedSplitResult forRoots(
        WindowedValue primaryRoot, WindowedValue residualRoot) {
      return new AutoValue_FnApiDoFnRunner_WindowedSplitResult(primaryRoot, residualRoot);
    }

    public abstract WindowedValue getPrimaryRoot();

    public abstract WindowedValue getResidualRoot();
  }

  private void processElementForSizedElementAndRestriction(
      WindowedValue<KV<KV<InputT, KV<RestrictionT, WatermarkEstimatorStateT>>, Double>> elem) {
    processElementForElementAndRestriction(elem.withValue(elem.getValue().getKey()));
  }

  private void processElementForElementAndRestriction(
      WindowedValue<KV<InputT, KV<RestrictionT, WatermarkEstimatorStateT>>> elem) {
    currentElement = elem.withValue(elem.getValue().getKey());
    try {
      Iterator<BoundedWindow> windowIterator =
          (Iterator<BoundedWindow>) elem.getWindows().iterator();
      while (windowIterator.hasNext()) {
        synchronized (splitLock) {
          currentRestriction = elem.getValue().getValue().getKey();
          currentWatermarkEstimatorState = elem.getValue().getValue().getValue();
          currentWindow = windowIterator.next();
          currentTracker =
              RestrictionTrackers.observe(
                  doFnInvoker.invokeNewTracker(processContext),
                  new ClaimObserver<PositionT>() {
                    @Override
                    public void onClaimed(PositionT position) {}

                    @Override
                    public void onClaimFailed(PositionT position) {}
                  });
          currentWatermarkEstimator =
              WatermarkEstimators.threadSafe(
                  doFnInvoker.invokeNewWatermarkEstimator(processContext));
        }

        // It is important to ensure that {@code splitLock} is not held during #invokeProcessElement
        DoFn.ProcessContinuation continuation = doFnInvoker.invokeProcessElement(processContext);
        // Ensure that all the work is done if the user tells us that they don't want to
        // resume processing.
        if (!continuation.shouldResume()) {
          currentTracker.checkDone();
          continue;
        }

        // Attempt to checkpoint the current restriction.
        HandlesSplits.SplitResult splitResult =
            trySplitForElementAndRestriction(0, continuation.resumeDelay());
        // After the user has chosen to resume processing later, the Runner may have stolen
        // the remainder of work through a split call so the above trySplit may return null. If so,
        // the current restriction must be done.
        if (splitResult == null) {
          currentTracker.checkDone();
          continue;
        }
        // Forward the split to the bundle level split listener.
        splitListener.split(splitResult.getPrimaryRoot(), splitResult.getResidualRoot());
      }
    } finally {
      synchronized (splitLock) {
        currentElement = null;
        currentRestriction = null;
        currentWatermarkEstimatorState = null;
        currentWindow = null;
        currentTracker = null;
        currentWatermarkEstimator = null;
      }
    }
  }

  /**
   * An abstract class which forwards split and progress calls allowing the implementer to choose
   * where input elements are sent.
   */
  private abstract class SplittableFnDataReceiver
      implements HandlesSplits, FnDataReceiver<WindowedValue> {
    @Override
    public SplitResult trySplit(double fractionOfRemainder) {
      return trySplitForElementAndRestriction(fractionOfRemainder, Duration.ZERO);
    }

    @Override
    public double getProgress() {
      Progress progress = FnApiDoFnRunner.this.getProgress();
      if (progress != null) {
        double totalWork = progress.getWorkCompleted() + progress.getWorkRemaining();
        if (totalWork > 0) {
          return progress.getWorkCompleted() / totalWork;
        }
      }
      return 0;
    }
  }

  private Progress getProgress() {
    synchronized (splitLock) {
      if (currentTracker instanceof RestrictionTracker.HasProgress) {
        return ((HasProgress) currentTracker).getProgress();
      }
    }
    return null;
  }

  private HandlesSplits.SplitResult trySplitForElementAndRestriction(
      double fractionOfRemainder, Duration resumeDelay) {
    KV<Instant, WatermarkEstimatorStateT> watermarkAndState;
    WindowedSplitResult windowedSplitResult;
    synchronized (splitLock) {
      // There is nothing to split if we are between element and restriction processing calls.
      if (currentTracker == null) {
        return null;
      }

      // Make sure to get the output watermark before we split to ensure that the lower bound
      // applies to the residual.
      watermarkAndState = currentWatermarkEstimator.getWatermarkAndState();
      SplitResult<RestrictionT> result = currentTracker.trySplit(fractionOfRemainder);
      if (result == null) {
        return null;
      }

      // We have a successful self split, either runner initiated or via a self checkpoint.
      windowedSplitResult =
          convertSplitResultToWindowedSplitResult.apply(result, watermarkAndState.getValue());
    }

    ByteString.Output primaryBytes = ByteString.newOutput();
    ByteString.Output residualBytes = ByteString.newOutput();
    try {
      Coder fullInputCoder = WindowedValue.getFullCoder(inputCoder, windowCoder);
      fullInputCoder.encode(windowedSplitResult.getPrimaryRoot(), primaryBytes);
      fullInputCoder.encode(windowedSplitResult.getResidualRoot(), residualBytes);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    BundleApplication.Builder primaryApplication =
        BundleApplication.newBuilder()
            .setTransformId(pTransformId)
            .setInputId(mainInputId)
            .setElement(primaryBytes.toByteString());
    BundleApplication.Builder residualApplication =
        BundleApplication.newBuilder()
            .setTransformId(pTransformId)
            .setInputId(mainInputId)
            .setElement(residualBytes.toByteString());

    if (!watermarkAndState.getKey().equals(GlobalWindow.TIMESTAMP_MIN_VALUE)) {
      for (String outputId : pTransform.getOutputsMap().keySet()) {
        residualApplication.putOutputWatermarks(
            outputId,
            org.apache.beam.vendor.grpc.v1p26p0.com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(watermarkAndState.getKey().getMillis() / 1000)
                .setNanos((int) (watermarkAndState.getKey().getMillis() % 1000) * 1000000)
                .build());
      }
    }
    return HandlesSplits.SplitResult.of(
        primaryApplication.build(),
        DelayedBundleApplication.newBuilder()
            .setApplication(residualApplication.build())
            .setRequestedTimeDelay(Durations.fromMillis(resumeDelay.getMillis()))
            .build());
  }

  private <K> void processTimer(String timerId, TimeDomain timeDomain, Timer<K> timer) {
    currentTimer = timer;
    currentTimeDomain = timeDomain;
    try {
      Iterator<BoundedWindow> windowIterator =
          (Iterator<BoundedWindow>) timer.getWindows().iterator();
      while (windowIterator.hasNext()) {
        currentWindow = windowIterator.next();
        doFnInvoker.invokeOnTimer(timerId, "", onTimerContext);
      }
    } finally {
      currentTimer = null;
      currentTimeDomain = null;
      currentWindow = null;
    }
  }

  private void finishBundle() throws Exception {
    for (TimerHandler timerHandler : timerHandlers.values()) {
      timerHandler.awaitCompletion();
    }
    for (TimerHandler timerHandler : timerHandlers.values()) {
      timerHandler.close();
    }

    doFnInvoker.invokeFinishBundle(finishBundleArgumentProvider);

    // TODO: Support caching state data across bundle boundaries.
    this.stateAccessor.finalizeState();
    this.stateAccessor = null;
  }

  private void tearDown() {
    doFnInvoker.invokeTeardown();
  }

  /** Outputs the given element to the specified set of consumers wrapping any exceptions. */
  private <T> void outputTo(
      Collection<FnDataReceiver<WindowedValue<T>>> consumers, WindowedValue<T> output) {
    if (currentWatermarkEstimator instanceof TimestampObservingWatermarkEstimator) {
      ((TimestampObservingWatermarkEstimator) currentWatermarkEstimator)
          .observeTimestamp(output.getTimestamp());
    }
    try {
      for (FnDataReceiver<WindowedValue<T>> consumer : consumers) {
        consumer.accept(output);
      }
    } catch (Throwable t) {
      throw UserCodeException.wrap(t);
    }
  }

  private class FnApiTimer<K> implements org.apache.beam.sdk.state.Timer {
    private final String timerId;
    private final K userKey;
    private final String dynamicTimerTag;
    private final TimeDomain timeDomain;
    private final Duration allowedLateness;
    private final Instant fireTimestamp;
    private final Instant elementTimestampOrTimerHoldTimestamp;
    private final BoundedWindow boundedWindow;
    private final PaneInfo paneInfo;

    private Instant outputTimestamp;
    private Duration period = Duration.ZERO;
    private Duration offset = Duration.ZERO;

    FnApiTimer(
        String timerId,
        K userKey,
        String dynamicTimerTag,
        BoundedWindow boundedWindow,
        Instant elementTimestampOrTimerHoldTimestamp,
        Instant elementTimestampOrTimerFireTimestamp,
        PaneInfo paneInfo) {
      this.timerId = timerId;
      this.userKey = userKey;
      this.dynamicTimerTag = dynamicTimerTag;
      this.elementTimestampOrTimerHoldTimestamp = elementTimestampOrTimerHoldTimestamp;
      this.boundedWindow = boundedWindow;
      this.paneInfo = paneInfo;

      TimerDeclaration timerDeclaration = doFnSignature.timerDeclarations().get(timerId);
      this.timeDomain = DoFnSignatures.getTimerSpecOrThrow(timerDeclaration, doFn).getTimeDomain();

      switch (timeDomain) {
        case EVENT_TIME:
          fireTimestamp = elementTimestampOrTimerFireTimestamp;
          break;
        case PROCESSING_TIME:
          fireTimestamp = new Instant(DateTimeUtils.currentTimeMillis());
          break;
        case SYNCHRONIZED_PROCESSING_TIME:
          fireTimestamp = new Instant(DateTimeUtils.currentTimeMillis());
          break;
        default:
          throw new IllegalArgumentException(String.format("Unknown time domain %s", timeDomain));
      }

      try {
        this.allowedLateness =
            rehydratedComponents
                .getPCollection(
                    pTransform.getInputsOrThrow(ParDoTranslation.getMainInputName(pTransform)))
                .getWindowingStrategy()
                .getAllowedLateness();
      } catch (IOException e) {
        throw new IllegalArgumentException(
            String.format("Unable to get allowed lateness for timer %s", timerId));
      }
    }

    @Override
    public void set(Instant absoluteTime) {
      // Verifies that the time domain of this timer is acceptable for absolute timers.
      if (!TimeDomain.EVENT_TIME.equals(timeDomain)) {
        throw new IllegalArgumentException(
            "Can only set relative timers in processing time domain. Use #setRelative()");
      }

      // Ensures that the target time is reasonable. For event time timers this means that the time
      // should be prior to window GC time.
      if (TimeDomain.EVENT_TIME.equals(timeDomain)) {
        Instant windowExpiry = LateDataUtils.garbageCollectionTime(currentWindow, allowedLateness);
        checkArgument(
            !absoluteTime.isAfter(windowExpiry),
            "Attempted to set event time timer for %s but that is after"
                + " the expiration of window %s",
            absoluteTime,
            windowExpiry);
      }

      output(absoluteTime);
    }

    @Override
    public void setRelative() {
      Instant target;
      if (period.equals(Duration.ZERO)) {
        target = fireTimestamp.plus(offset);
      } else {
        long millisSinceStart = fireTimestamp.plus(offset).getMillis() % period.getMillis();
        target =
            millisSinceStart == 0
                ? fireTimestamp
                : fireTimestamp.plus(period).minus(millisSinceStart);
      }
      target = minTargetAndGcTime(target);
      output(target);
    }

    @Override
    public org.apache.beam.sdk.state.Timer offset(Duration offset) {
      this.offset = offset;
      return this;
    }

    @Override
    public org.apache.beam.sdk.state.Timer align(Duration period) {
      this.period = period;
      return this;
    }

    @Override
    public org.apache.beam.sdk.state.Timer withOutputTimestamp(Instant outputTime) {
      this.outputTimestamp = outputTime;
      return this;
    }
    /**
     * For event time timers the target time should be prior to window GC time. So it returns
     * min(time to set, GC Time of window).
     */
    private Instant minTargetAndGcTime(Instant target) {
      if (TimeDomain.EVENT_TIME.equals(timeDomain)) {
        Instant windowExpiry = LateDataUtils.garbageCollectionTime(currentWindow, allowedLateness);
        if (target.isAfter(windowExpiry)) {
          return windowExpiry;
        }
      }
      return target;
    }

    private void output(Instant scheduledTime) {
      if (outputTimestamp != null) {
        checkArgument(
            !outputTimestamp.isBefore(elementTimestampOrTimerHoldTimestamp),
            "output timestamp %s should be after input message timestamp or output timestamp of firing timers %s",
            outputTimestamp,
            elementTimestampOrTimerHoldTimestamp);
      }

      // Output timestamp is set to the delivery time if not initialized by an user.
      if (outputTimestamp == null && TimeDomain.EVENT_TIME.equals(timeDomain)) {
        outputTimestamp = scheduledTime;
      }

      // For processing timers
      if (outputTimestamp == null) {
        // For processing timers output timestamp will be:
        // 1) timestamp of input element
        // OR
        // 2) hold timestamp of firing timer.
        outputTimestamp = elementTimestampOrTimerHoldTimestamp;
      }

      Instant windowExpiry = LateDataUtils.garbageCollectionTime(currentWindow, allowedLateness);
      if (TimeDomain.EVENT_TIME.equals(timeDomain)) {
        checkArgument(
            !outputTimestamp.isAfter(scheduledTime),
            "Attempted to set an event-time timer with an output timestamp of %s that is"
                + " after the timer firing timestamp %s",
            outputTimestamp,
            scheduledTime);
        checkArgument(
            !scheduledTime.isAfter(windowExpiry),
            "Attempted to set an event-time timer with a firing timestamp of %s that is"
                + " after the expiration of window %s",
            scheduledTime,
            windowExpiry);
      } else {
        checkArgument(
            !outputTimestamp.isAfter(windowExpiry),
            "Attempted to set a processing-time timer with an output timestamp of %s that is"
                + " after the expiration of window %s",
            outputTimestamp,
            windowExpiry);
      }

      TimerHandler<K> consumer = (TimerHandler) timerHandlers.get(timerId);
      try {
        consumer.accept(
            Timer.of(
                userKey,
                dynamicTimerTag,
                Collections.singletonList(boundedWindow),
                scheduledTime,
                outputTimestamp,
                paneInfo));
      } catch (Throwable t) {
        throw UserCodeException.wrap(t);
      }
    }
  }

  private static class FnApiTimerMap implements TimerMap {
    FnApiTimerMap() {}

    @Override
    public void set(String timerId, Instant absoluteTime) {}

    @Override
    public org.apache.beam.sdk.state.Timer get(String timerId) {
      return null;
    }
  }

  private class StartBundleArgumentProvider extends BaseArgumentProvider<InputT, OutputT> {
    private class Context extends DoFn<InputT, OutputT>.StartBundleContext {
      Context() {
        doFn.super();
      }

      @Override
      public PipelineOptions getPipelineOptions() {
        return pipelineOptions;
      }
    }

    private final Context context = new Context();

    @Override
    public DoFn<InputT, OutputT>.StartBundleContext startBundleContext(DoFn<InputT, OutputT> doFn) {
      return context;
    }

    @Override
    public PipelineOptions pipelineOptions() {
      return pipelineOptions;
    }

    @Override
    public BundleFinalizer bundleFinalizer() {
      return bundleFinalizer;
    }

    @Override
    public String getErrorContext() {
      return "FnApiDoFnRunner/StartBundle";
    }
  }

  private class FinishBundleArgumentProvider extends BaseArgumentProvider<InputT, OutputT> {
    private class Context extends DoFn<InputT, OutputT>.FinishBundleContext {
      Context() {
        doFn.super();
      }

      @Override
      public PipelineOptions getPipelineOptions() {
        return pipelineOptions;
      }

      @Override
      public void output(OutputT output, Instant timestamp, BoundedWindow window) {
        outputTo(
            mainOutputConsumers, WindowedValue.of(output, timestamp, window, PaneInfo.NO_FIRING));
      }

      @Override
      public <T> void output(TupleTag<T> tag, T output, Instant timestamp, BoundedWindow window) {
        Collection<FnDataReceiver<WindowedValue<T>>> consumers =
            (Collection) localNameToConsumer.get(tag.getId());
        if (consumers == null) {
          throw new IllegalArgumentException(String.format("Unknown output tag %s", tag));
        }
        outputTo(consumers, WindowedValue.of(output, timestamp, window, PaneInfo.NO_FIRING));
      }
    }

    private final Context context = new Context();

    @Override
    public DoFn<InputT, OutputT>.FinishBundleContext finishBundleContext(
        DoFn<InputT, OutputT> doFn) {
      return context;
    }

    @Override
    public PipelineOptions pipelineOptions() {
      return pipelineOptions;
    }

    @Override
    public BundleFinalizer bundleFinalizer() {
      return bundleFinalizer;
    }

    @Override
    public String getErrorContext() {
      return "FnApiDoFnRunner/FinishBundle";
    }
  }

  /**
   * Provides arguments for a {@link DoFnInvoker} for {@link DoFn.ProcessElement @ProcessElement}.
   */
  private class ProcessBundleContext extends DoFn<InputT, OutputT>.ProcessContext
      implements DoFnInvoker.ArgumentProvider<InputT, OutputT> {

    private ProcessBundleContext() {
      doFn.super();
    }

    @Override
    public BoundedWindow window() {
      return currentWindow;
    }

    @Override
    public PaneInfo paneInfo(DoFn<InputT, OutputT> doFn) {
      return pane();
    }

    @Override
    public DoFn<InputT, OutputT>.StartBundleContext startBundleContext(DoFn<InputT, OutputT> doFn) {
      throw new UnsupportedOperationException(
          "Cannot access StartBundleContext outside of @StartBundle method.");
    }

    @Override
    public DoFn<InputT, OutputT>.FinishBundleContext finishBundleContext(
        DoFn<InputT, OutputT> doFn) {
      throw new UnsupportedOperationException(
          "Cannot access FinishBundleContext outside of @FinishBundle method.");
    }

    @Override
    public DoFn<InputT, OutputT>.ProcessContext processContext(DoFn<InputT, OutputT> doFn) {
      return this;
    }

    @Override
    public InputT element(DoFn<InputT, OutputT> doFn) {
      return element();
    }

    @Override
    public Object sideInput(String tagId) {
      return sideInput(sideInputMapping.get(tagId));
    }

    @Override
    public Object schemaElement(int index) {
      SerializableFunction converter = doFnSchemaInformation.getElementConverters().get(index);
      return converter.apply(element());
    }

    @Override
    public Instant timestamp(DoFn<InputT, OutputT> doFn) {
      return timestamp();
    }

    @Override
    public String timerId(DoFn<InputT, OutputT> doFn) {
      throw new UnsupportedOperationException(
          "Cannot access timerId as parameter outside of @OnTimer method.");
    }

    @Override
    public TimeDomain timeDomain(DoFn<InputT, OutputT> doFn) {
      throw new UnsupportedOperationException(
          "Cannot access time domain outside of @ProcessTimer method.");
    }

    @Override
    public OutputReceiver<OutputT> outputReceiver(DoFn<InputT, OutputT> doFn) {
      return DoFnOutputReceivers.windowedReceiver(this, null);
    }

    @Override
    public OutputReceiver<Row> outputRowReceiver(DoFn<InputT, OutputT> doFn) {
      return DoFnOutputReceivers.rowReceiver(this, null, mainOutputSchemaCoder);
    }

    @Override
    public MultiOutputReceiver taggedOutputReceiver(DoFn<InputT, OutputT> doFn) {
      return DoFnOutputReceivers.windowedMultiReceiver(this, outputCoders);
    }

    @Override
    public BundleFinalizer bundleFinalizer() {
      return bundleFinalizer;
    }

    @Override
    public Object restriction() {
      return currentRestriction;
    }

    @Override
    public DoFn<InputT, OutputT>.OnTimerContext onTimerContext(DoFn<InputT, OutputT> doFn) {
      throw new UnsupportedOperationException(
          "Cannot access OnTimerContext outside of @OnTimer methods.");
    }

    @Override
    public RestrictionTracker<?, ?> restrictionTracker() {
      return currentTracker;
    }

    @Override
    public State state(String stateId, boolean alwaysFetched) {
      StateDeclaration stateDeclaration = doFnSignature.stateDeclarations().get(stateId);
      checkNotNull(stateDeclaration, "No state declaration found for %s", stateId);
      StateSpec<?> spec;
      try {
        spec = (StateSpec<?>) stateDeclaration.field().get(doFn);
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
      State state = spec.bind(stateId, stateAccessor);
      if (alwaysFetched) {
        return (State) ((ReadableState) state).readLater();
      } else {
        return state;
      }
    }

    @Override
    public org.apache.beam.sdk.state.Timer timer(String timerId) {
      checkState(
          currentElement.getValue() instanceof KV,
          "Accessing timer in unkeyed context. Current element is not a KV: %s.",
          currentElement.getValue());

      // For the initial timestamps we pass in the current elements timestamp for the hold timestamp
      // and the current element's timestamp which will be used for the fire timestamp if this
      // timer is in the EVENT time domain.
      return new FnApiTimer(
          timerId,
          ((KV) currentElement.getValue()).getKey(),
          "",
          currentWindow,
          currentElement.getTimestamp(),
          currentElement.getTimestamp(),
          currentElement.getPane());
    }

    @Override
    public TimerMap timerFamily(String tagId) {
      // TODO: implement timerFamily
      return null;
    }

    @Override
    public PipelineOptions getPipelineOptions() {
      return pipelineOptions;
    }

    @Override
    public PipelineOptions pipelineOptions() {
      return pipelineOptions;
    }

    @Override
    public void output(OutputT output) {
      outputWithTimestamp(output, currentElement.getTimestamp());
    }

    @Override
    public void outputWithTimestamp(OutputT output, Instant timestamp) {
      outputTo(
          mainOutputConsumers,
          WindowedValue.of(output, timestamp, currentWindow, currentElement.getPane()));
    }

    @Override
    public <T> void output(TupleTag<T> tag, T output) {
      outputWithTimestamp(tag, output, currentElement.getTimestamp());
    }

    @Override
    public <T> void outputWithTimestamp(TupleTag<T> tag, T output, Instant timestamp) {
      Collection<FnDataReceiver<WindowedValue<T>>> consumers =
          (Collection) localNameToConsumer.get(tag.getId());
      if (consumers == null) {
        throw new IllegalArgumentException(String.format("Unknown output tag %s", tag));
      }
      outputTo(
          consumers, WindowedValue.of(output, timestamp, currentWindow, currentElement.getPane()));
    }

    @Override
    public InputT element() {
      return currentElement.getValue();
    }

    @Override
    public <T> T sideInput(PCollectionView<T> view) {
      return stateAccessor.get(view, currentWindow);
    }

    @Override
    public Instant timestamp() {
      return currentElement.getTimestamp();
    }

    @Override
    public PaneInfo pane() {
      return currentElement.getPane();
    }

    @Override
    public Object watermarkEstimatorState() {
      return currentWatermarkEstimatorState;
    }

    @Override
    public WatermarkEstimator<?> watermarkEstimator() {
      return currentWatermarkEstimator;
    }
  }

  /** Provides arguments for a {@link DoFnInvoker} for {@link DoFn.OnTimer @OnTimer}. */
  private class OnTimerContext extends BaseArgumentProvider<InputT, OutputT> {
    private class Context extends DoFn<InputT, OutputT>.OnTimerContext {
      private Context() {
        doFn.super();
      }

      @Override
      public PipelineOptions getPipelineOptions() {
        return pipelineOptions;
      }

      @Override
      public BoundedWindow window() {
        return currentWindow;
      }

      @Override
      public void output(OutputT output) {
        outputTo(
            mainOutputConsumers,
            WindowedValue.of(
                output, currentTimer.getHoldTimestamp(), currentWindow, currentTimer.getPane()));
      }

      @Override
      public void outputWithTimestamp(OutputT output, Instant timestamp) {
        checkArgument(
            !currentTimer.getHoldTimestamp().isAfter(timestamp),
            "Output time %s can not be before timer timestamp %s.",
            timestamp,
            currentTimer.getHoldTimestamp());
        outputTo(
            mainOutputConsumers,
            WindowedValue.of(output, timestamp, currentWindow, currentTimer.getPane()));
      }

      @Override
      public <T> void output(TupleTag<T> tag, T output) {
        Collection<FnDataReceiver<WindowedValue<T>>> consumers =
            (Collection) localNameToConsumer.get(tag.getId());
        if (consumers == null) {
          throw new IllegalArgumentException(String.format("Unknown output tag %s", tag));
        }
        outputTo(
            consumers,
            WindowedValue.of(
                output, currentTimer.getHoldTimestamp(), currentWindow, currentTimer.getPane()));
      }

      @Override
      public <T> void outputWithTimestamp(TupleTag<T> tag, T output, Instant timestamp) {
        checkArgument(
            !currentTimer.getHoldTimestamp().isAfter(timestamp),
            "Output time %s can not be before timer timestamp %s.",
            timestamp,
            currentTimer.getHoldTimestamp());
        Collection<FnDataReceiver<WindowedValue<T>>> consumers =
            (Collection) localNameToConsumer.get(tag.getId());
        if (consumers == null) {
          throw new IllegalArgumentException(String.format("Unknown output tag %s", tag));
        }
        outputTo(
            consumers, WindowedValue.of(output, timestamp, currentWindow, currentTimer.getPane()));
      }

      @Override
      public TimeDomain timeDomain() {
        return currentTimeDomain;
      }

      @Override
      public Instant fireTimestamp() {
        return currentTimer.getFireTimestamp();
      }

      @Override
      public Instant timestamp() {
        return currentTimer.getHoldTimestamp();
      }
    }

    private final Context context = new Context();

    @Override
    public BoundedWindow window() {
      return currentWindow;
    }

    @Override
    public Instant timestamp(DoFn<InputT, OutputT> doFn) {
      return currentTimer.getHoldTimestamp();
    }

    @Override
    public TimeDomain timeDomain(DoFn<InputT, OutputT> doFn) {
      return currentTimeDomain;
    }

    @Override
    public OutputReceiver<OutputT> outputReceiver(DoFn<InputT, OutputT> doFn) {
      return DoFnOutputReceivers.windowedReceiver(context, null);
    }

    @Override
    public OutputReceiver<Row> outputRowReceiver(DoFn<InputT, OutputT> doFn) {
      return DoFnOutputReceivers.rowReceiver(context, null, mainOutputSchemaCoder);
    }

    @Override
    public MultiOutputReceiver taggedOutputReceiver(DoFn<InputT, OutputT> doFn) {
      return DoFnOutputReceivers.windowedMultiReceiver(context);
    }

    @Override
    public DoFn<InputT, OutputT>.OnTimerContext onTimerContext(DoFn<InputT, OutputT> doFn) {
      return context;
    }

    @Override
    public State state(String stateId, boolean alwaysFetched) {
      StateDeclaration stateDeclaration = doFnSignature.stateDeclarations().get(stateId);
      checkNotNull(stateDeclaration, "No state declaration found for %s", stateId);
      StateSpec<?> spec;
      try {
        spec = (StateSpec<?>) stateDeclaration.field().get(doFn);
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
      State state = spec.bind(stateId, stateAccessor);
      if (alwaysFetched) {
        return (State) ((ReadableState) state).readLater();
      } else {
        return state;
      }
    }

    @Override
    public org.apache.beam.sdk.state.Timer timer(String timerId) {
      return new FnApiTimer(
          timerId,
          currentTimer.getUserKey(),
          currentTimer.getDynamicTimerTag(),
          currentWindow,
          currentTimer.getHoldTimestamp(),
          currentTimer.getFireTimestamp(),
          currentTimer.getPane());
    }

    @Override
    public TimerMap timerFamily(String tagId) {
      // TODO: implement timerFamily
      return super.timerFamily(tagId);
    }

    @Override
    public PipelineOptions pipelineOptions() {
      return pipelineOptions;
    }

    @Override
    public String getErrorContext() {
      return "FnApiDoFnRunner/OnTimer";
    }
  }
}
