/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.scopes.impl

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirTypeAlias
import org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef
import org.jetbrains.kotlin.fir.declarations.utils.classId
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutor
import org.jetbrains.kotlin.fir.resolve.substitution.substitutorByMap
import org.jetbrains.kotlin.fir.scopes.DelicateScopeAPI
import org.jetbrains.kotlin.fir.scopes.FirContainingNamesAwareScope
import org.jetbrains.kotlin.fir.scopes.withReplacedSessionOrNull
import org.jetbrains.kotlin.fir.symbols.ConeTypeParameterLookupTag
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassifierSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.impl.ConeTypeParameterTypeImpl
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.SmartList
import org.jetbrains.kotlin.utils.addToStdlib.shouldNotBeCalled

abstract class FirNestedClassifierScope(val klass: FirClass, val useSiteSession: FirSession) : FirContainingNamesAwareScope() {
    protected abstract fun getNestedClassSymbol(name: Name): FirClassLikeSymbol<*>?

    override fun processClassifiersByNameWithSubstitution(
        name: Name,
        processor: (FirClassifierSymbol<*>, ConeSubstitutor) -> Unit
    ) {
        val matchedClass = getNestedClassSymbol(name) ?: return
        val substitutor = if (klass.typeParameters.isEmpty()) {
            ConeSubstitutor.Empty
        } else {
            val substitution = klass.typeParameters.associate {
                it.symbol to it.toConeType()
            }
            substitutorByMap(substitution, useSiteSession, allowIdenticalSubstitution = true)
        }
        processor(matchedClass, substitutor)
    }

    abstract fun isEmpty(): Boolean

    override fun getCallableNames(): Set<Name> = emptySet()

    override val scopeOwnerLookupNames: List<String> =
        if (klass.isLocal) emptyList()
        else SmartList(klass.classId.asFqNameString())

    @DelicateScopeAPI
    abstract override fun withReplacedSessionOrNull(newSession: FirSession, newScopeSession: ScopeSession): FirNestedClassifierScope?
}

class FirNestedClassifierScopeImpl(klass: FirClass, useSiteSession: FirSession) : FirNestedClassifierScope(klass, useSiteSession) {
    /**
     * This index should be lazily calculated as [FirClass.declarations] may lead to indefinite recursion
     * at least for Java classes as their resolution is not under control.
     *
     * Additionally, this index might not be used in the Analysis API mode, so its instant calculation
     * may be redundant.
     *
     * Issue: KT-74097
     */
    private val classIndex: Map<Name, FirClassLikeSymbol<*>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val result = mutableMapOf<Name, FirClassLikeSymbol<*>>()
        for (declaration in klass.declarations) {
            when (declaration) {
                is FirRegularClass -> result[declaration.name] = declaration.symbol
                is FirTypeAlias -> result[declaration.name] = declaration.symbol
                else -> {}
            }
        }

        result
    }

    override fun getNestedClassSymbol(name: Name): FirClassLikeSymbol<*>? {
        return classIndex[name]
    }

    override fun isEmpty(): Boolean = classIndex.isEmpty()

    override fun getClassifierNames(): Set<Name> = classIndex.keys

    @DelicateScopeAPI
    override fun withReplacedSessionOrNull(newSession: FirSession, newScopeSession: ScopeSession): FirNestedClassifierScopeImpl {
        return FirNestedClassifierScopeImpl(klass, newSession)
    }
}

class FirCompositeNestedClassifierScope(
    val scopes: List<FirNestedClassifierScope>,
    klass: FirClass,
    useSiteSession: FirSession
) : FirNestedClassifierScope(klass, useSiteSession) {
    override fun getNestedClassSymbol(name: Name): FirRegularClassSymbol? {
        shouldNotBeCalled()
    }

    override fun processClassifiersByNameWithSubstitution(name: Name, processor: (FirClassifierSymbol<*>, ConeSubstitutor) -> Unit) {
        scopes.forEach { it.processClassifiersByNameWithSubstitution(name, processor) }
    }

    override fun isEmpty(): Boolean {
        return scopes.all { it.isEmpty() }
    }

    override fun getClassifierNames(): Set<Name> {
        return scopes.flatMapTo(mutableSetOf()) { it.getClassifierNames() }
    }

    @DelicateScopeAPI
    override fun withReplacedSessionOrNull(newSession: FirSession, newScopeSession: ScopeSession): FirCompositeNestedClassifierScope {
        val newScopes = scopes.withReplacedSessionOrNull(newSession, newScopeSession) ?: scopes
        return FirCompositeNestedClassifierScope(newScopes, klass, newSession)
    }
}

fun FirTypeParameterRef.toConeType(): ConeTypeParameterType = symbol.toConeType()

fun FirTypeParameterSymbol.toConeType(): ConeTypeParameterType = toConeType(false)

fun FirTypeParameterSymbol.toConeType(isNullable: Boolean): ConeTypeParameterType =
    ConeTypeParameterTypeImpl(ConeTypeParameterLookupTag(this), isNullable)
