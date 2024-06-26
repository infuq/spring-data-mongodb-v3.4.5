/*
 * Copyright 2017-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.mongodb.core

import kotlin.reflect.KClass

/**
 * Extension for [ExecutableUpdateOperation.update] providing a [KClass] based variant.
 *
 * @author Christoph Strobl
 * @since 2.0
 */
@Deprecated("Since 2.2, use the reified variant", replaceWith = ReplaceWith("update<T>()"))
fun <T : Any> ExecutableUpdateOperation.update(entityClass: KClass<T>): ExecutableUpdateOperation.ExecutableUpdate<T> =
		update(entityClass.java)

/**
 * Extension for [ExecutableUpdateOperation.update] leveraging reified type parameters.
 *
 * @author Christoph Strobl
 * @since 2.0
 */
inline fun <reified T : Any> ExecutableUpdateOperation.update(): ExecutableUpdateOperation.ExecutableUpdate<T> =
		update(T::class.java)
