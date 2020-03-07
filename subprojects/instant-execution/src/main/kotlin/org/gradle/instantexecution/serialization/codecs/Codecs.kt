/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.instantexecution.serialization.codecs

import org.gradle.api.Project
import org.gradle.api.Script
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.DependencyConstraintSet
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.artifacts.LenientConfiguration
import org.gradle.api.artifacts.ResolutionStrategy
import org.gradle.api.artifacts.ResolvedConfiguration
import org.gradle.api.artifacts.dsl.ComponentMetadataHandler
import org.gradle.api.artifacts.dsl.ComponentModuleMetadataHandler
import org.gradle.api.artifacts.dsl.DependencyConstraintHandler
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.dsl.DependencyLockingHandler
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.query.ArtifactResolutionQuery
import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.artifacts.type.ArtifactTypeContainer
import org.gradle.api.attributes.AttributeMatchingStrategy
import org.gradle.api.attributes.AttributesSchema
import org.gradle.api.attributes.CompatibilityRuleChain
import org.gradle.api.attributes.DisambiguationRuleChain
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.ProjectLayout
import org.gradle.api.initialization.Settings
import org.gradle.api.internal.artifacts.dsl.dependencies.ProjectFinder
import org.gradle.api.internal.artifacts.transform.ArtifactTransformActionScheme
import org.gradle.api.internal.artifacts.transform.ArtifactTransformListener
import org.gradle.api.internal.artifacts.transform.ArtifactTransformParameterScheme
import org.gradle.api.internal.attributes.ImmutableAttributesFactory
import org.gradle.api.internal.file.FileCollectionFactory
import org.gradle.api.internal.file.FileFactory
import org.gradle.api.internal.file.FileLookup
import org.gradle.api.internal.file.FileOperations
import org.gradle.api.internal.file.FilePropertyFactory
import org.gradle.api.internal.file.FileResolver
import org.gradle.api.internal.file.TemporaryFileProvider
import org.gradle.api.internal.file.collections.DirectoryFileTreeFactory
import org.gradle.api.internal.project.ProjectStateRegistry
import org.gradle.api.internal.provider.ValueSourceProviderFactory
import org.gradle.api.invocation.Gradle
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.services.internal.BuildServiceRegistryInternal
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskDependency
import org.gradle.api.tasks.util.PatternSet
import org.gradle.api.tasks.util.internal.PatternSpecFactory
import org.gradle.execution.plan.TaskNodeFactory
import org.gradle.initialization.BuildRequestMetaData
import org.gradle.instantexecution.serialization.ownerServiceCodec
import org.gradle.instantexecution.serialization.reentrant
import org.gradle.instantexecution.serialization.unsupported
import org.gradle.internal.Factory
import org.gradle.internal.event.ListenerManager
import org.gradle.internal.fingerprint.FileCollectionFingerprinterRegistry
import org.gradle.internal.hash.ClassLoaderHierarchyHasher
import org.gradle.internal.isolation.IsolatableFactory
import org.gradle.internal.nativeintegration.filesystem.FileSystem
import org.gradle.internal.operations.BuildOperationExecutor
import org.gradle.internal.operations.BuildOperationListenerManager
import org.gradle.internal.reflect.Instantiator
import org.gradle.internal.serialize.BaseSerializerFactory.BOOLEAN_SERIALIZER
import org.gradle.internal.serialize.BaseSerializerFactory.BYTE_SERIALIZER
import org.gradle.internal.serialize.BaseSerializerFactory.CHAR_SERIALIZER
import org.gradle.internal.serialize.BaseSerializerFactory.DOUBLE_SERIALIZER
import org.gradle.internal.serialize.BaseSerializerFactory.FILE_SERIALIZER
import org.gradle.internal.serialize.BaseSerializerFactory.FLOAT_SERIALIZER
import org.gradle.internal.serialize.BaseSerializerFactory.INTEGER_SERIALIZER
import org.gradle.internal.serialize.BaseSerializerFactory.LONG_SERIALIZER
import org.gradle.internal.serialize.BaseSerializerFactory.PATH_SERIALIZER
import org.gradle.internal.serialize.BaseSerializerFactory.SHORT_SERIALIZER
import org.gradle.internal.serialize.BaseSerializerFactory.STRING_SERIALIZER
import org.gradle.internal.serialize.HashCodeSerializer
import org.gradle.internal.snapshot.ValueSnapshotter
import org.gradle.internal.state.ManagedFactoryRegistry
import org.gradle.kotlin.dsl.*
import org.gradle.process.ExecOperations
import org.gradle.process.internal.ExecActionFactory
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry
import org.gradle.workers.WorkerExecutor
import java.io.FileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executor
import java.util.concurrent.ThreadFactory


class Codecs(
    directoryFileTreeFactory: DirectoryFileTreeFactory,
    fileCollectionFactory: FileCollectionFactory,
    fileLookup: FileLookup,
    filePropertyFactory: FilePropertyFactory,
    fileResolver: FileResolver,
    instantiator: Instantiator,
    listenerManager: ListenerManager,
    projectStateRegistry: ProjectStateRegistry,
    taskNodeFactory: TaskNodeFactory,
    fingerprinterRegistry: FileCollectionFingerprinterRegistry,
    projectFinder: ProjectFinder,
    buildOperationExecutor: BuildOperationExecutor,
    classLoaderHierarchyHasher: ClassLoaderHierarchyHasher,
    isolatableFactory: IsolatableFactory,
    valueSnapshotter: ValueSnapshotter,
    buildServiceRegistry: BuildServiceRegistryInternal,
    managedFactoryRegistry: ManagedFactoryRegistry,
    parameterScheme: ArtifactTransformParameterScheme,
    actionScheme: ArtifactTransformActionScheme,
    attributesFactory: ImmutableAttributesFactory,
    transformListener: ArtifactTransformListener,
    valueSourceProviderFactory: ValueSourceProviderFactory,
    patternSetFactory: Factory<PatternSet>,
    fileSystem: FileSystem,
    fileFactory: FileFactory
) {

    val userTypesCodec = BindingsBackedCodec {

        unsupportedTypes()

        baseTypes()

        bind(HashCodeSerializer())
        bind(BrokenValueCodec)

        providerTypes(filePropertyFactory, buildServiceRegistry, valueSourceProviderFactory)

        bind(ListenerBroadcastCodec(listenerManager))
        bind(LoggerCodec)

        fileCollectionTypes(directoryFileTreeFactory, fileCollectionFactory, patternSetFactory, fileSystem, fileFactory)

        bind(ApiTextResourceAdapterCodec)

        bind(ClosureCodec)
        bind(GroovyMetaClassCodec)

        // Dependency management types
        bind(ArtifactCollectionCodec(fileCollectionFactory))
        bind(ImmutableAttributeCodec(attributesFactory))
        bind(AttributeContainerCodec(attributesFactory))
        bind(TransformationNodeReferenceCodec)

        bind(DefaultCopySpecCodec(fileResolver, fileCollectionFactory, instantiator))
        bind(DestinationRootCopySpecCodec(fileResolver))

        bind(TaskReferenceCodec)

        bind(ownerServiceCodec<ProviderFactory>())
        bind(ownerServiceCodec<ObjectFactory>())
        bind(ownerServiceCodec<WorkerExecutor>())
        bind(ownerServiceCodec<ProjectLayout>())
        bind(ownerServiceCodec<PatternSpecFactory>())
        bind(ownerServiceCodec<FileResolver>())
        bind(ownerServiceCodec<Instantiator>())
        bind(ownerServiceCodec<FileCollectionFactory>())
        bind(ownerServiceCodec<FileSystemOperations>())
        bind(ownerServiceCodec<FileOperations>())
        bind(ownerServiceCodec<BuildOperationExecutor>())
        bind(ownerServiceCodec<ToolingModelBuilderRegistry>())
        bind(ownerServiceCodec<ExecOperations>())
        bind(ownerServiceCodec<ExecActionFactory>())
        bind(ownerServiceCodec<BuildOperationListenerManager>())
        bind(ownerServiceCodec<BuildRequestMetaData>())
        bind(ownerServiceCodec<ListenerManager>())
        bind(ownerServiceCodec<TemporaryFileProvider>())
        bind(ServicesCodec())

        bind(ProxyCodec)

        bind(SerializableWriteObjectCodec())
        bind(SerializableWriteReplaceCodec())

        // This protects the BeanCodec against StackOverflowErrors but
        // we can still get them for the other codecs, for instance,
        // with deeply nested Lists, deeply nested Maps, etc.
        bind(reentrant(BeanCodec()))
    }

    private
    fun BindingsBuilder.unsupportedTypes() {

        // Live JVM state
        bind(unsupported<ClassLoader>())
        bind(unsupported<Thread>())
        bind(unsupported<ThreadFactory>())
        bind(unsupported<Executor>())
        bind(unsupported<InputStream>())
        bind(unsupported<OutputStream>())
        bind(unsupported<FileDescriptor>())
        bind(unsupported<RandomAccessFile>())
        bind(unsupported<Socket>())
        bind(unsupported<ServerSocket>())

        // Gradle Scripts
        bind(unsupported<Script>())
        bind(unsupported<KotlinScript>())

        // Gradle Build Model
        bind(unsupported<Gradle>())
        bind(unsupported<Settings>())
        bind(unsupported<Project>())
        bind(unsupported<TaskContainer>())
        bind(unsupported<TaskDependency>())
        bind(unsupported<SourceSetContainer>())
        bind(unsupported<SourceSet>())

        // Dependency Resolution Services
        bind(unsupported<ConfigurationContainer>())
        // bind(unsupported<Configuration>()) // TODO this breaks lots of core plugins
        bind(unsupported<ResolutionStrategy>())
        bind(unsupported<ResolvedConfiguration>())
        bind(unsupported<LenientConfiguration>())
        bind(unsupported<DependencyConstraintSet>())
        bind(unsupported<RepositoryHandler>())
        bind(unsupported<ArtifactRepository>())
        bind(unsupported<DependencyHandler>())
        bind(unsupported<DependencyConstraintHandler>())
        bind(unsupported<ComponentMetadataHandler>())
        bind(unsupported<ComponentModuleMetadataHandler>())
        bind(unsupported<ArtifactTypeContainer>())
        bind(unsupported<AttributesSchema>())
        bind(unsupported<AttributeMatchingStrategy<*>>())
        bind(unsupported<CompatibilityRuleChain<*>>())
        bind(unsupported<DisambiguationRuleChain<*>>())
        bind(unsupported<ArtifactResolutionQuery>())
        bind(unsupported<DependencySet>())
        bind(unsupported<Dependency>())
        bind(unsupported<DependencyLockingHandler>())
    }

    val internalTypesCodec = BindingsBackedCodec {

        baseTypes()

        providerTypes(filePropertyFactory, buildServiceRegistry, valueSourceProviderFactory)
        fileCollectionTypes(directoryFileTreeFactory, fileCollectionFactory, patternSetFactory, fileSystem, fileFactory)

        bind(TaskNodeCodec(projectStateRegistry, userTypesCodec, taskNodeFactory))
        bind(InitialTransformationNodeCodec(buildOperationExecutor, transformListener))
        bind(ChainedTransformationNodeCodec(buildOperationExecutor, transformListener))
        bind(ActionNodeCodec)
        bind(ResolvableArtifactCodec)
        bind(TransformationStepCodec(projectStateRegistry, fingerprinterRegistry, projectFinder))
        bind(DefaultTransformerCodec(userTypesCodec, buildOperationExecutor, classLoaderHierarchyHasher, isolatableFactory, valueSnapshotter, fileCollectionFactory, fileLookup, parameterScheme, actionScheme))
        bind(LegacyTransformerCodec(actionScheme))

        bind(IsolatedManagedValueCodec(managedFactoryRegistry))
        bind(IsolatedImmutableManagedValueCodec(managedFactoryRegistry))
        bind(IsolatedArrayCodec)
        bind(IsolatedSetCodec)
        bind(IsolatedListCodec)
        bind(IsolatedMapCodec)
        bind(MapEntrySnapshotCodec)
        bind(IsolatedEnumValueSnapshotCodec)
        bind(StringValueSnapshotCodec)
        bind(IntegerValueSnapshotCodec)
        bind(FileValueSnapshotCodec)
        bind(BooleanValueSnapshotCodec)
        bind(NullValueSnapshotCodec)

        bind(NotImplementedCodec)
    }

    private
    fun BindingsBuilder.providerTypes(filePropertyFactory: FilePropertyFactory, buildServiceRegistry: BuildServiceRegistryInternal, valueSourceProviderFactory: ValueSourceProviderFactory) {
        val nestedCodec = FixedValueReplacingProviderCodec(valueSourceProviderFactory, buildServiceRegistry)
        bind(ListPropertyCodec(nestedCodec))
        bind(SetPropertyCodec(nestedCodec))
        bind(MapPropertyCodec(nestedCodec))
        bind(DirectoryPropertyCodec(filePropertyFactory, nestedCodec))
        bind(RegularFilePropertyCodec(filePropertyFactory, nestedCodec))
        bind(PropertyCodec(nestedCodec))
        bind(BuildServiceProviderCodec(buildServiceRegistry))
        bind(ValueSourceProviderCodec(valueSourceProviderFactory))
        bind(ProviderCodec(nestedCodec))
    }

    private
    fun BindingsBuilder.fileCollectionTypes(directoryFileTreeFactory: DirectoryFileTreeFactory, fileCollectionFactory: FileCollectionFactory, patternSetFactory: Factory<PatternSet>, fileSystem: FileSystem, fileFactory: FileFactory) {
        bind(DirectoryCodec(fileFactory))
        bind(RegularFileCodec(fileFactory))
        bind(FileTreeCodec(directoryFileTreeFactory, patternSetFactory, fileSystem))
        bind(ConfigurableFileCollectionCodec(fileCollectionFactory))
        bind(FileCollectionCodec(fileCollectionFactory))
        bind(IntersectPatternSetCodec)
        bind(PatternSetCodec)
    }

    private
    fun BindingsBuilder.baseTypes() {
        bind(STRING_SERIALIZER)
        bind(BOOLEAN_SERIALIZER)
        bind(INTEGER_SERIALIZER)
        bind(CHAR_SERIALIZER)
        bind(SHORT_SERIALIZER)
        bind(LONG_SERIALIZER)
        bind(BYTE_SERIALIZER)
        bind(FLOAT_SERIALIZER)
        bind(DOUBLE_SERIALIZER)
        bind(FILE_SERIALIZER)
        bind(PATH_SERIALIZER)
        bind(ClassCodec)
        bind(MethodCodec)

        // Only serialize certain List implementations
        bind(arrayListCodec)
        bind(linkedListCodec)
        bind(ImmutableListCodec)

        // Only serialize certain Set implementations for now, as some custom types extend Set (eg DomainObjectContainer)
        bind(linkedHashSetCodec)
        bind(hashSetCodec)
        bind(treeSetCodec)
        bind(ImmutableSetCodec)

        // Only serialize certain Map implementations for now, as some custom types extend Map (eg DefaultManifest)
        bind(linkedHashMapCodec)
        bind(hashMapCodec)
        bind(treeMapCodec)
        bind(concurrentHashMapCodec)
        bind(ImmutableMapCodec)

        bind(arrayCodec)
        bind(EnumCodec)
    }
}
