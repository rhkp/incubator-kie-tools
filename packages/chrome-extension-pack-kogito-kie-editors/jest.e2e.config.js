/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

const { config, babelTransform, typescriptTransform } = require("@kie-tools/jest-base/jest.config");

/** @type {import('ts-jest').JestConfigWithTsJest} */
module.exports = {
  ...config,
  testEnvironment: "jsdom",
  reporters: [
    "default",
    [
      "jest-junit",
      {
        suiteName: "Chrome Extension for BPMN and DMN",
        outputFile: "./dist-tests-e2e/junit-report.xml",
        classNameTemplate: "Chrome Extension for BPMN and DMN ::",
        titleTemplate: "{title}",
        ancestorSeparator: " :: ",
        usePathForSuiteName: "true",
        addFileAttribute: "true",
      },
    ],
  ],
  transform: {
    ...babelTransform,
    ...typescriptTransform,
  },
  testRegex: ["e2e-tests/tests/.*Test.ts"],
  testTimeout: 100000,
};
