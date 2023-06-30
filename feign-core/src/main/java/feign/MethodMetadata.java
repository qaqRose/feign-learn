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
package feign;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.SetMultimap;
import com.google.common.reflect.TypeToken;

import java.io.Serializable;
import java.util.List;

public final class MethodMetadata implements Serializable {
  MethodMetadata() {
  }

  /**
   * 方法的javadoc
   * @see feign.Feign#configKey(java.lang.reflect.Method)
   */
  private String configKey;
  private transient TypeToken<?> returnType;

  /**
   * 方法参数支持 {@code URI}
   * urlIndex设置 URI在方法参数列表的下标位置
   * 没有则为空
   */
  private Integer urlIndex;
  /**
   * request body 在方法参数列表的下标位置
   * 没有则为空
   */
  private Integer bodyIndex;
  private RequestTemplate template = new RequestTemplate();

  /**
   * 表单参数名称
   * @see javax.ws.rs.FormParam
   */
  private List<String> formParams = Lists.newArrayList();

  /**
   * 存储请求方法的一些参数的位置下标（顺序）和名称，蚕例如path或query
   * key表示位置下标，value表示 PathParam 的name 或 PathParam 的name
   * @see javax.ws.rs.PathParam
   * @see javax.ws.rs.QueryParam
   * @see javax.ws.rs.HeaderParam
   * @see javax.ws.rs.FormParam
   *
   */
  private SetMultimap<Integer, String> indexToName = LinkedHashMultimap.create();

  /**
   * @see Feign#configKey(java.lang.reflect.Method)
   */
  public String configKey() {
    return configKey;
  }

  MethodMetadata configKey(String configKey) {
    this.configKey = configKey;
    return this;
  }

  public TypeToken<?> returnType() {
    return returnType;
  }

  MethodMetadata returnType(TypeToken<?> returnType) {
    this.returnType = returnType;
    return this;
  }

  public Integer urlIndex() {
    return urlIndex;
  }

  MethodMetadata urlIndex(Integer urlIndex) {
    this.urlIndex = urlIndex;
    return this;
  }

  public Integer bodyIndex() {
    return bodyIndex;
  }

  MethodMetadata bodyIndex(Integer bodyIndex) {
    this.bodyIndex = bodyIndex;
    return this;
  }

  public RequestTemplate template() {
    return template;
  }

  public List<String> formParams() {
    return formParams;
  }

  public SetMultimap<Integer, String> indexToName() {
    return indexToName;
  }

  private static final long serialVersionUID = 1L;
}
