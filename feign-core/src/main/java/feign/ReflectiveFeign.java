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

import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.Maps;
import com.google.common.reflect.AbstractInvocationHandler;
import com.google.common.reflect.Reflection;
import dagger.Provides;
import feign.MethodHandler.Factory;
import feign.Request.Options;
import feign.codec.BodyEncoder;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import feign.codec.FormEncoder;
import feign.codec.ToStringDecoder;

import javax.inject.Inject;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static feign.Contract.parseAndValidatateMetadata;
import static java.lang.String.format;

/**
 * 反射生成feign实例工厂
 */
@SuppressWarnings("rawtypes")
public class ReflectiveFeign extends Feign {

  /**
   * 函数方法，通过代理目标类获取代理类的方法封装{@code MethodHandler}
   * feign代理类 和 方法 map
   * 通过构造器注入
   */
  private final Function<Target, Map<String, MethodHandler>> targetToHandlersByName;

  @Inject
  ReflectiveFeign(Function<Target, Map<String, MethodHandler>> targetToHandlersByName) {
    this.targetToHandlersByName = targetToHandlersByName;
  }

  /**
   * 创建代理类的实体
   * 解析代理类的方法（url/注解/参数等）
   * 保存到{@link feign.ReflectiveFeign.FeignInvocationHandler#methodToHandler}中
   *
   * creates an api binding to the {@code target}. As this invokes reflection,
   * care should be taken to cache the result.
   */
  @Override public <T> T newInstance(Target<T> target) {
    Map<String, MethodHandler> nameToHandler = targetToHandlersByName.apply(target);
    Builder<Method, MethodHandler> methodToHandler = ImmutableMap.builder();
    for (Method method : target.type().getDeclaredMethods()) {
      if (method.getDeclaringClass() == Object.class) {  // object的方法不代理
        continue;
      }
      // 这里使用Method作为key, 而不是string,所以需要做一层装换
      methodToHandler.put(method, nameToHandler.get(Feign.configKey(method)));
    }
    FeignInvocationHandler handler = new FeignInvocationHandler(target, methodToHandler.build());
    return Reflection.newProxy(target.type(), handler);
  }

  /**
   * 反射调用 处理器
   */
  static class FeignInvocationHandler extends AbstractInvocationHandler {

    private final Target target;

    /**
     * 对象所有方法map
     * 
     */
    private final Map<Method, MethodHandler> methodToHandler;

    FeignInvocationHandler(Target target, ImmutableMap<Method, MethodHandler> methodToHandler) {
      this.target = checkNotNull(target, "target");
      this.methodToHandler = checkNotNull(methodToHandler, "methodToHandler for %s", target);
    }

    @Override
    protected Object handleInvocation(Object proxy, Method method, Object[] args) throws Throwable {
      return methodToHandler.get(method).invoke(args);
    }

    @Override public int hashCode() {
      return target.hashCode();
    }

    @Override public boolean equals(Object obj) {
      if (this == obj)
        return true;
      if (FeignInvocationHandler.class != obj.getClass())
        return false;
      FeignInvocationHandler that = FeignInvocationHandler.class.cast(obj);
      return this.target.equals(that.target);
    }

    @Override public String toString() {
      return Objects.toStringHelper("").add("name", target.name()).add("url", target.url()).toString();
    }
  }

  @dagger.Module(complete = false,// Config
      injects = Feign.class, library = true// provides Feign
  )
  public static class Module {

    @Provides Feign provideFeign(ReflectiveFeign in) {
      return in;
    }

    @Provides
    Function<Target, Map<String, MethodHandler>> targetToHandlersByName(ParseHandlersByName parseHandlersByName) {
      return parseHandlersByName;
    }
  }

  private static IllegalStateException noConfig(String configKey, Class<?> type) {
    return new IllegalStateException(format("no configuration for %s present for %s!", configKey,
        type.getSimpleName()));
  }

  /**
   * 内部类，解析处理器
   */
  static final class ParseHandlersByName implements Function<Target, Map<String, MethodHandler>> {
    /**
     * 请求的可选参数
     * 例如： connectTimeoutMillis 连接超时毫秒
     *       readTimeoutMillis 读取超时毫秒
     */
    private final Map<String, Options> options;
    private final Map<String, BodyEncoder> bodyEncoders;
    private final Map<String, FormEncoder> formEncoders;
    private final Map<String, Decoder> decoders;
    private final Map<String, ErrorDecoder> errorDecoders;
    private final Factory factory;

    @Inject
    ParseHandlersByName(Map<String, Options> options,
                        Map<String, BodyEncoder> bodyEncoders,
                        Map<String, FormEncoder> formEncoders,
                        Map<String, Decoder> decoders,
                        Map<String, ErrorDecoder> errorDecoders,
                        Factory factory) {
      this.options = options;
      this.bodyEncoders = bodyEncoders;
      this.formEncoders = formEncoders;
      this.decoders = decoders;
      this.factory = factory;
      this.errorDecoders = errorDecoders;
    }

    /**
     *
     * @param key
     * @return
     */
    @Override
    public Map<String, MethodHandler> apply(Target key) {
      // 解析代理类的所有方法
      Set<MethodMetadata> metadata = parseAndValidatateMetadata(key.type());
      ImmutableMap.Builder<String, MethodHandler> builder = ImmutableMap.builder();
      for (MethodMetadata md : metadata) {
        Options options = forMethodOrClass(this.options, md.configKey());
        if (options == null) {
          options = new Options();
        }
        Decoder decoder = forMethodOrClass(decoders, md.configKey());
        if (decoder == null
                && (md.returnType().getRawType() == void.class
                || md.returnType().getRawType() == Response.class)) {
          // 方法返回类型是 Void或者Response，使用默认ToStringDecoder解析器
          decoder = new ToStringDecoder();
        }
        if (decoder == null) {
          throw noConfig(md.configKey(), Decoder.class);
        }
        ErrorDecoder errorDecoder = forMethodOrClass(errorDecoders, md.configKey());
        if (errorDecoder == null) {
          errorDecoder = ErrorDecoder.DEFAULT;
        }
        Function<Object[], RequestTemplate> buildTemplateFromArgs;
        if (!md.formParams().isEmpty() && !md.template().bodyTemplate().isPresent()) {
          FormEncoder formEncoder = forMethodOrClass(formEncoders, md.configKey());
          if (formEncoder == null) {
            throw noConfig(md.configKey(), FormEncoder.class);
          }
          buildTemplateFromArgs = new BuildFormEncodedTemplateFromArgs(md, formEncoder);
        } else if (md.bodyIndex() != null) {
          BodyEncoder bodyEncoder = forMethodOrClass(bodyEncoders, md.configKey());
          if (bodyEncoder == null) {
            throw noConfig(md.configKey(), BodyEncoder.class);
          }
          buildTemplateFromArgs = new BuildBodyEncodedTemplateFromArgs(md, bodyEncoder);
        } else {
          buildTemplateFromArgs = new BuildTemplateFromArgs(md);
        }
        builder.put(md.configKey(),
            factory.create(key, md, buildTemplateFromArgs, options, decoder, errorDecoder));
      }
      return builder.build();
    }
  }

  /**
   * 构建form表单参数
   * 真正构建参数的地方
   */
  private static class BuildTemplateFromArgs implements Function<Object[], RequestTemplate> {
    protected final MethodMetadata metadata;

    private BuildTemplateFromArgs(MethodMetadata metadata) {
      this.metadata = metadata;
    }

    @Override
    public RequestTemplate apply(Object[] argv) {
      RequestTemplate mutable = new RequestTemplate(metadata.template());
      if (metadata.urlIndex() != null) {
        int urlIndex = metadata.urlIndex();
        checkArgument(argv[urlIndex] != null, "URI parameter %s was null", urlIndex);
        // 插入url地址 （方法参数级别）
        mutable.insert(0, String.valueOf(argv[urlIndex]));
      }
      // 解析参数
      ImmutableMap.Builder<String, Object> varBuilder = ImmutableMap.builder();
      for (Entry<Integer, Collection<String>> entry : metadata.indexToName().asMap().entrySet()) {
        Object value = argv[entry.getKey()];
        if (value != null) { // Null values are skipped.
          for (String name : entry.getValue())
            // 组成真正的key-value
            // 前面保存的是 下标和名称, 这里根据下标获取对应的值，并返回名称-值的map
            varBuilder.put(name, value);
        }
      }
      // 返回解析完成RequestTemplate
      return resolve(argv, mutable, varBuilder.build());
    }

    protected RequestTemplate resolve(Object[] argv, RequestTemplate mutable, ImmutableMap<String, Object> variables) {
      return mutable.resolve(variables);
    }
  }

  /**
   * 表单编码
   */
  private static class BuildFormEncodedTemplateFromArgs extends BuildTemplateFromArgs {
    private final FormEncoder formEncoder;

    private BuildFormEncodedTemplateFromArgs(MethodMetadata metadata, FormEncoder formEncoder) {
      super(metadata);
      this.formEncoder = formEncoder;
    }

    @Override
    protected RequestTemplate resolve(Object[] argv, RequestTemplate mutable, ImmutableMap<String, Object> variables) {
      formEncoder.encodeForm(Maps.filterKeys(variables, Predicates.in(metadata.formParams())), mutable);
      return super.resolve(argv, mutable, variables);
    }
  }

  /**
   * Body编码
   * 针对一些json请求，
   */
  private static class BuildBodyEncodedTemplateFromArgs extends BuildTemplateFromArgs {
    private final BodyEncoder bodyEncoder;

    private BuildBodyEncodedTemplateFromArgs(MethodMetadata metadata, BodyEncoder bodyEncoder) {
      super(metadata);
      this.bodyEncoder = bodyEncoder;
    }

    @Override
    protected RequestTemplate resolve(Object[] argv, RequestTemplate mutable, ImmutableMap<String, Object> variables) {
      Object body = argv[metadata.bodyIndex()];
      checkArgument(body != null, "Body parameter %s was null", metadata.bodyIndex());
      bodyEncoder.encodeBody(body, mutable);
      return super.resolve(argv, mutable, variables);
    }
  }

  static <T> T forMethodOrClass(Map<String, T> config, String configKey) {
    if (config.containsKey(configKey)) {
      return config.get(configKey);
    }
    String classKey = toClassKey(configKey);
    if (config.containsKey(classKey)) {
      return config.get(classKey);
    }
    return null;
  }

  public static String toClassKey(String methodKey) {
    return methodKey.substring(0, methodKey.indexOf('#'));
  }
}
