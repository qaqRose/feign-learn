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

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.TypeToken;

import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URI;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.net.HttpHeaders.ACCEPT;
import static com.google.common.net.HttpHeaders.CONTENT_TYPE;

/**
 * 解析方法注解和参数注解
 * 无状态，只是提供解析的过程，可能理解为工具类，
 * 只提供了两个静态方法
 * Defines what annotations and values are valid on interfaces.
 */
public final class Contract {

  /**
   * 解析类方法
   */
  public static ImmutableSet<MethodMetadata> parseAndValidatateMetadata(Class<?> declaring) {
    ImmutableSet.Builder<MethodMetadata> builder = ImmutableSet.builder();
    for (Method method : declaring.getDeclaredMethods()) {
      if (method.getDeclaringClass() == Object.class) {  // Object类的方法
        continue;
      }
      builder.add(parseAndValidatateMetadata(method));
    }
    return builder.build();
  }

  /**
   * 把feign实例方法解析成MethodMetadata
   * @param method
   * @return
   */
  public static MethodMetadata parseAndValidatateMetadata(Method method) {
    MethodMetadata data = new MethodMetadata();
    // 保存方法返回的泛型类型
    data.returnType(TypeToken.of(method.getGenericReturnType()));
    // 解析方法的javadoc方法串
    data.configKey(Feign.configKey(method));

    // 方法解析
    for (Annotation methodAnnotation : method.getAnnotations()) {
      Class<? extends Annotation> annotationType = methodAnnotation.annotationType();
      HttpMethod http = annotationType.getAnnotation(HttpMethod.class);
      if (http != null) {
        // http方法重复校验
        checkState(data.template().method() == null,
            "Method %s contains multiple HTTP methods. Found: %s and %s", method.getName(), data.template()
            .method(), http.value());
        data.template().method(http.value());
      } else if (annotationType == RequestTemplate.Body.class) {
        String body = RequestTemplate.Body.class.cast(methodAnnotation).value();
        if (body.indexOf('{') == -1) {
          data.template().body(body);
        } else {
          data.template().bodyTemplate(body);  // 请求body模板
        }
      } else if (annotationType == Path.class) {
        // http 请求path, 追加到url
        data.template().append(Path.class.cast(methodAnnotation).value());
      } else if (annotationType == Produces.class) {
        // 解析http请求头  Content-Type
        data.template().header(CONTENT_TYPE, Joiner.on(',').join(((Produces) methodAnnotation).value()));
      } else if (annotationType == Consumes.class) {
        // 解析http请求头 Accept
        data.template().header(ACCEPT, Joiner.on(',').join(((Consumes) methodAnnotation).value()));
      }
    }
    checkState(data.template().method() != null, "Method %s not annotated with HTTP method type (ex. GET, POST)",
        method.getName());
    Class<?>[] parameterTypes = method.getParameterTypes();

    // 参数注解
    Annotation[][] parameterAnnotationArrays = method.getParameterAnnotations();
    int count = parameterAnnotationArrays.length;  // 参数个数
    for (int i = 0; i < count; i++) {
      boolean hasHttpAnnotation = false;

      Class<?> parameterType = parameterTypes[i];
      Annotation[] parameterAnnotations = parameterAnnotationArrays[i];
      if (parameterAnnotations != null) {
        for (Annotation parameterAnnotation : parameterAnnotations) {
          Class<? extends Annotation> annotationType = parameterAnnotation.annotationType();
          if (annotationType == PathParam.class) {
            data.indexToName().put(i, PathParam.class.cast(parameterAnnotation).value());
            hasHttpAnnotation = true;
          } else if (annotationType == QueryParam.class) {
            String name = QueryParam.class.cast(parameterAnnotation).value();
            data.template().query(
                name,
                ImmutableList.<String>builder()
                        .addAll(data.template().queries().get(name))   // 查询串的值 （因为内部会removeAll, 所以需要select后再insert）
                        .add(String.format("{%s}", name)).build());    // 包装{}的名称，存在requestTemplate的queries
                                                                        // todo:暂时不知道有何妙用, 应该是为了替换字符串
            data.indexToName().put(i, name);
            hasHttpAnnotation = true;
          } else if (annotationType == HeaderParam.class) {
            String name = HeaderParam.class.cast(parameterAnnotation).value();
            data.template().header(
                name,
                ImmutableList.<String>builder().addAll(data.template().headers().get(name))
                    .add(String.format("{%s}", name)).build());
            data.indexToName().put(i, name);
            hasHttpAnnotation = true;
          } else if (annotationType == FormParam.class) {
            String form = FormParam.class.cast(parameterAnnotation).value();
            data.formParams().add(form);
            data.indexToName().put(i, form);
            hasHttpAnnotation = true;
          }
        }
      }

      if (parameterType == URI.class) {
        data.urlIndex(i);
      } else if (!hasHttpAnnotation) {
        checkState(data.formParams().isEmpty(), "Body parameters cannot be used with @FormParam parameters.");
        checkState(data.bodyIndex() == null, "Method has too many Body parameters: %s", method);
        data.bodyIndex(i);
      }
    }
    return data;
  }
}
