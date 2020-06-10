/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.codegen.ir

import org.jetbrains.kotlin.codegen.AbstractCompileKotlinAgainstKotlinTest
import org.jetbrains.kotlin.test.TargetBackend

abstract class AbstractIrCompileKotlinAgainstKotlinTest : AbstractCompileKotlinAgainstKotlinTest() {
    override val backend = TargetBackend.JVM_IR
}
