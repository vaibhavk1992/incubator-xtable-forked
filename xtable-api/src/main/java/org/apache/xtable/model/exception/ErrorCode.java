/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
 
package org.apache.xtable.model.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {
  INVALID_CONFIGURATION(10001),
  INVALID_PARTITION_SPEC(10002),
  INVALID_PARTITION_VALUE(10003),
  READ_EXCEPTION(10004),
  UPDATE_EXCEPTION(10005),
  INVALID_SCHEMA(10006),
  UNSUPPORTED_SCHEMA_TYPE(10007),
  UNSUPPORTED_FEATURE(10008),
  PARSE_EXCEPTION(10009),
  CATALOG_REFRESH_EXCEPTION(10010),
  CATALOG_SYNC_GENERIC_EXCEPTION(10011);

  private final int errorCode;

  ErrorCode(int errorCode) {
    this.errorCode = errorCode;
  }
}
