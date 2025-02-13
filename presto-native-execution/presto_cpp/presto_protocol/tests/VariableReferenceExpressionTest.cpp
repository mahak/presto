/*
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
#include <gtest/gtest.h>

#include "presto_cpp/main/common/tests/test_json.h"

using namespace facebook::presto::protocol;

class VariableReferenceExpressionTest : public ::testing::Test {};

TEST_F(VariableReferenceExpressionTest, basic) {
  std::string str = R"(
        {
            "@type":"variable",
            "name":"segment",
            "type":"integer"
        }
    )";

  json j = json::parse(str);
  VariableReferenceExpression p = j;

  // Check some values ...
  ASSERT_EQ(p.name, "segment");
  ASSERT_EQ(p.type, "integer");

  testJsonRoundtrip(j, p);

  ASSERT_EQ(json_map_key(p), "segment<integer>") << "... json_map_key";
  ASSERT_EQ(
      static_cast<json>(VariableReferenceExpression("segment<integer>")),
      static_cast<json>(p))
      << "... string constructor";
}
