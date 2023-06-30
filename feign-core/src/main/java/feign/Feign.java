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

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import dagger.ObjectGraph;
import dagger.Provides;
import feign.Request.Options;
import feign.Target.HardCodedTarget;
import feign.Wire.NoOpWire;
import feign.codec.BodyEncoder;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import feign.codec.FormEncoder;

import javax.net.ssl.SSLSocketFactory;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Feign抽象类，用来创建目标http api的工厂
 * Feign's purpose is to ease development against http apis that feign
 * restfulness.
 * <p/>
 * In implementation, Feign is a {@link Feign#newInstance factory} for
 * generating {@link Target targeted} http apis.
 */
public abstract class Feign {

  /**
   * 工厂类产生实例的方法，生成指定{@code target}的type的实例
   * 由子类决定生产的方式
   * 例如
   * @see feign.ReflectiveFeign
   * 通过反射生成代理类
   * Returns a new instance of an HTTP API, defined by annotations in the
   * {@link Feign Contract}, for the specified {@code target}. You should
   * cache this result.
   */
  public abstract <T> T newInstance(Target<T> target);

  /**
   * 创建一个http api 实例
   * todo: 后面怎么解析不直接传入url, 或者有没有其他实现 除了HardCodedTarget
   * @param apiType  实例类型
   * @param url      域名地址(直接合方法上的地址拼接)
   * @param modules  dagger模块（使用门槛还是比较高，提供了 GsonModule
   * @return
   * @param <T>
   */
  public static <T> T create(Class<T> apiType, String url, Object... modules) {
    return create(new HardCodedTarget<T>(apiType, url), modules);
  }

  /**
   * Shortcut to {@link #newInstance(Target) create} a single {@code targeted}
   * http api using {@link ReflectiveFeign reflection}.
   */
  public static <T> T create(Target<T> target, Object... modules) {
    return create(modules).newInstance(target);
  }

  /**
   * Returns a {@link ReflectiveFeign reflective} factory for generating
   * {@link Target targeted} http apis.
   */
  public static Feign create(Object... modules) {
    Object[] modulesForGraph = ImmutableList.builder() //
        .add(new Defaults()) //  默认模块实现
        .add(new ReflectiveFeign.Module()) // 反射实现模块
        .add(Optional.fromNullable(modules).or(new Object[]{})).build().toArray();
    return ObjectGraph.create(modulesForGraph).get(Feign.class);
  }

  /**
   * Returns an {@link ObjectGraph Dagger ObjectGraph} that can inject a
   * {@link ReflectiveFeign reflective} Feign.
   */
  public static ObjectGraph createObjectGraph(Object... modules) {
    Object[] modulesForGraph = ImmutableList.builder() //
        .add(new Defaults()) //
        .add(new ReflectiveFeign.Module()) //
        .add(Optional.fromNullable(modules).or(new Object[]{})).build().toArray();
    return ObjectGraph.create(modulesForGraph);
  }

  @dagger.Module(complete = false, injects = Feign.class, library = true)
  public static class Defaults {

    @Provides SSLSocketFactory sslSocketFactory() {
      return SSLSocketFactory.class.cast(SSLSocketFactory.getDefault());
    }

    @Provides Client httpClient(Client.Default client) {
      return client;
    }

    @Provides Retryer retryer() {
      return new Retryer.Default();
    }

    @Provides Wire noOp() {
      return new NoOpWire();
    }

    @Provides Map<String, Options> noOptions() {
      return ImmutableMap.of();
    }

    @Provides Map<String, BodyEncoder> noBodyEncoders() {
      return ImmutableMap.of();
    }

    @Provides Map<String, FormEncoder> noFormEncoders() {
      return ImmutableMap.of();
    }

    @Provides Map<String, Decoder> noDecoders() {
      return ImmutableMap.of();
    }

    @Provides Map<String, ErrorDecoder> noErrorDecoders() {
      return ImmutableMap.of();
    }
  }

  /**
   *
   * 将方法按javadoc的方法格式解析
   * 例如
   * class Hello {
   *    public void say(String name)
   * }
   *
   * sag 解成 Hello#save(String)
   * 好处应该是确保唯一
   * <p/>
   * Configuration keys are formatted as unresolved <a href=
   * "http://docs.oracle.com/javase/6/docs/jdk/api/javadoc/doclet/com/sun/javadoc/SeeTag.html"
   * >see tags</a>.
   * <p/>
   * For example.
   * <ul>
   * <li>{@code Route53}: would match a class such as
   * {@code denominator.route53.Route53}
   * <li>{@code Route53#list()}: would match a method such as
   * {@code denominator.route53.Route53#list()}
   * <li>{@code Route53#listAt(Marker)}: would match a method such as
   * {@code denominator.route53.Route53#listAt(denominator.route53.Marker)}
   * <li>{@code Route53#listByNameAndType(String, String)}: would match a
   * method such as {@code denominator.route53.Route53#listAt(String, String)}
   * </ul>
   * <p/>
   * Note that there is no whitespace expected in a key!
   */
  public static String configKey(Method method) {
    StringBuilder builder = new StringBuilder();
    builder.append(method.getDeclaringClass().getSimpleName());
    builder.append('#').append(method.getName()).append('(');

    for (Class<?> param : method.getParameterTypes()) {
      builder.append(param.getSimpleName()).append(',');
    }

    if (method.getParameterTypes().length > 0) {
      builder.deleteCharAt(builder.length() - 1);
    }

    return builder.append(')').toString();
  }

  Feign() {

  }
}
