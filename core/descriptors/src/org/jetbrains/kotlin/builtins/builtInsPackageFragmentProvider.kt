/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.builtins

import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentProvider
import org.jetbrains.kotlin.descriptors.PackageFragmentProviderImpl
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.serialization.deserialization.*
import org.jetbrains.kotlin.storage.StorageManager
import java.io.InputStream

fun createBuiltInPackageFragmentProvider(
        storageManager: StorageManager,
        module: ModuleDescriptor,
        packageFqNames: Set<FqName>,
        classDescriptorFactory: ClassDescriptorFactory,
        additionalSupertypes: AdditionalSupertypes = AdditionalSupertypes.None,
        loadResource: (String) -> InputStream?
): PackageFragmentProvider {
    val packageFragments = packageFqNames.map { fqName ->
        BuiltInsPackageFragment(fqName, storageManager, module, loadResource)
    }
    val provider = PackageFragmentProviderImpl(packageFragments)

    val notFoundClasses = NotFoundClasses(storageManager, module)
    val localClassResolver = LocalClassResolverImpl()

    val components = DeserializationComponents(
            storageManager,
            module,
            DeserializedClassDataFinder(provider),
            AnnotationAndConstantLoaderImpl(module, notFoundClasses, BuiltInSerializerProtocol),
            provider,
            localClassResolver,
            ErrorReporter.DO_NOTHING,
            LookupTracker.DO_NOTHING,
            FlexibleTypeCapabilitiesDeserializer.ThrowException,
            classDescriptorFactory,
            notFoundClasses,
            additionalSupertypes = additionalSupertypes
    )

    localClassResolver.setDeserializationComponents(components)

    for (packageFragment in packageFragments) {
        packageFragment.components = components
    }

    return provider
}
