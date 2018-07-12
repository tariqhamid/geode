/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.internal.statistics;

import org.apache.geode.statistics.StatisticDescriptor;
import org.apache.geode.statistics.Statistics;

/**
 * Identifies a specific instance of a stat as defined by a StatisticDescriptor.
 * <p>
 * A StatisticsType contains any number of StatisticDescriptors. A StatisticsType may describe one
 * or more Statistics instances, while a StatisticDescriptor may describe one or more StatisticId
 * instances.
 *
 * @since GemFire 7.0
 */
public interface StatisticId {

  /** Returns the descriptor that this stat is an instance of */
  StatisticDescriptor getStatisticDescriptor();

  /** Returns the statistics instance which owns this stat instance */
  Statistics getStatistics();
}
