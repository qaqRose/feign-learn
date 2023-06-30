/*
 * Copyright 2013 Netflix, Inc.
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
package feign.codec;

import feign.RequestTemplate;

import java.util.Map;

/**
 * form表单编码器
 */
public interface FormEncoder {

  /**
   * 解析form表单参数到请求
   *
   * FormParam encoding
   * <p/>
   * If any parameters are annotated with {@link javax.ws.rs.FormParam}, they will be
   * collected and passed as {code formParams}
   * <p/>
   * <pre>
   * &#064;POST
   * &#064;Path(&quot;/&quot;)
   * Session login(@FormParam(&quot;username&quot;) String username, @FormParam(&quot;password&quot;) String password);
   * </pre>
   *
   * @param formParams Object instance to convert.
   * @param base       template to encode the {@code object} into.
   */
  void encodeForm(Map<String, ?> formParams, RequestTemplate base);
}
