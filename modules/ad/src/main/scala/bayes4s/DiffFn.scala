/*
 * Copyright 2026 bayes4s contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package bayes4s

/** A scalar function written once, for every interpreter.
  *
  * The rank-2 type is the point: the body cannot hold on to a value of type `A` beyond the call,
  * so a tape index can never outlive the tape it indexes into. That is what makes the mutable tape
  * of [[Reverse]] safe without a single runtime check.
  */
trait DiffFn:
  def apply[A: Real](x: IArray[A]): A

final case class ValueAndGradient(value: Double, gradient: IArray[Double])
