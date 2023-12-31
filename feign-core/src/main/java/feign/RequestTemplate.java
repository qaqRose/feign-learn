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

import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.Objects.equal;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.net.HttpHeaders.CONTENT_LENGTH;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * 用于构建http请求，线程不安全
 * Builds a request to an http target. Not thread safe.
 * <p/>
 * <h4>relationship to JAXRS 2.0</h4>
 * <p/>
 * A combination of {@code javax.ws.rs.client.WebTarget} and
 * {@code javax.ws.rs.client.Invocation.Builder}, ensuring you can modify any
 * part of the request. However, this object is mutable, so needs to be guarded
 * with the copy constructor.
 */
public final class RequestTemplate implements Serializable {

  /**
   * A templatized form for a PUT or POST command. Values of {@link javax.ws.rs.PathParam},
   * {@link javax.ws.rs.QueryParam}, {@link javax.ws.rs.HeaderParam}, and {@link javax.ws.rs.FormParam} can be
   * used are passed to the template.
   * <p/>
   * ex.
   * <p/>
   * <pre>
   * &#064;Body(&quot;&lt;v01:getResourceRecordsOfZone&gt;&lt;zoneName&gt;{zoneName}&lt;/zoneName&gt;&lt;rrType&gt;0&lt;/rrType&gt;&lt;/v01:getResourceRecordsOfZone&gt;&quot;)
   * List&lt;Record&gt; listByZone(@PayloadParam(&quot;zoneName&quot;) String zoneName);
   * </pre>
   * <p/>
   * Note that if you'd like curly braces literally in the body, urlencode
   * them first.
   *
   * @see RequestTemplate#expand(String, Map)
   */
  @Target(METHOD)
  @Retention(RUNTIME)
  public @interface Body {
    String value();
  }

  /**
   * http 请求方法
   * @see javax.ws.rs.HttpMethod
   */
  private String method;
  /* final to encourage mutable use vs replacing the object. */
  private StringBuilder url = new StringBuilder();
  private final ListMultimap<String, String> queries = LinkedListMultimap.create();
  private final ListMultimap<String, String> headers = LinkedListMultimap.create();
  private Optional<String> body = Optional.absent();
  private Optional<String> bodyTemplate = Optional.absent();

  public RequestTemplate() {

  }

  /* Copy constructor. Use this when making templates. */
  public RequestTemplate(RequestTemplate toCopy) {
    checkNotNull(toCopy, "toCopy");
    this.method = toCopy.method;
    this.url.append(toCopy.url);
    this.queries.putAll(toCopy.queries);
    this.headers.putAll(toCopy.headers);
    this.body = toCopy.body;
    this.bodyTemplate = toCopy.bodyTemplate;
  }

  /**
   * 解析http url和请求头，主要是针对一些模板变量`{}`进行替换，没有找对应key的继续保留
   *
   * Targets a template to this target, adding the {@link #url() base url} and
   * any authentication headers.
   * <p/>
   * <p/>
   * For example:
   * <p/>
   * <pre>
   * public Request apply(RequestTemplate input) {
   *     input.insert(0, url());
   *     input.replaceHeader(&quot;X-Auth&quot;, currentToken);
   *     return input.asRequest();
   * }
   * </pre>
   * <p/>
   * <h4>relationship to JAXRS 2.0</h4>
   * <p/>
   * This call is similar to
   * {@code javax.ws.rs.client.WebTarget.resolveTemplates(templateValues, true)}
   * , except that the template values apply to any part of the request, not
   * just the URL
   */
  public RequestTemplate resolve(Map<String, ?> unencoded) {
    Map<String, String> encoded = Maps.newLinkedHashMap();
    for (Entry<String, ?> entry : unencoded.entrySet()) {
      encoded.put(entry.getKey(), urlEncode(String.valueOf(entry.getValue())));
    }
    // 展开查询串的一些模板变量
    String queryLine = expand(queryLine(), encoded);
    queries.clear();
    pullAnyQueriesOutOfUrl(new StringBuilder(queryLine));
    // url上的模板变量也解析一次， 例如 Path
    String resolvedUrl = expand(url.toString(), encoded).replace("%2F", "/");
    url = new StringBuilder(resolvedUrl);

    // 解析header
    ListMultimap<String, String> resolvedHeaders = LinkedListMultimap.create();
    for (Entry<String, String> entry : headers.entries()) {
      String value = null;
      if (entry.getValue().indexOf('{') == 0) {
        value = String.valueOf(unencoded.get(entry.getKey()));
      } else {
        value = entry.getValue();
      }
      if (value != null)
        resolvedHeaders.put(entry.getKey(), value);
    }
    headers.clear();
    headers.putAll(resolvedHeaders);
    if (bodyTemplate.isPresent())
      body(urlDecode(expand(bodyTemplate.get(), unencoded)));
    return this;
  }

  /* roughly analogous to {@code javax.ws.rs.client.Target.request()}. */
  public Request request() {
    return new Request(method, new StringBuilder(url).append(queryLine()).toString(),
        ImmutableListMultimap.copyOf(headers), body);
  }

  /**
   * url 编码
   * @param arg
   * @return
   */
  private static String urlDecode(String arg) {
    try {
      return URLDecoder.decode(arg, UTF_8.name());
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * url utf8 编码， 保证请求安全
   */
  private static String urlEncode(Object arg) {
    try {
      return URLEncoder.encode(String.valueOf(arg), UTF_8.name());
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * 把{paramName}替换真的参数值， paramName是在解析方法注解时，使用`{%s}`模板保存的字符串
   *
   * Expands a {@code template}, such as {@code username}, using the {@code variables} supplied. Any unresolved
   * parameters will remain.
   * <p/>
   * Note that if you'd like curly braces literally in the {@code template},
   * urlencode them first.
   *
   * @param template  URI template that can be in level 1 <a
   *                  href="http://tools.ietf.org/html/rfc6570">RFC6570</a> form.
   * @param variables to the URI template
   * @return expanded template, leaving any unresolved parameters literal
   */
  public static String expand(String template, Map<String, ?> variables) {
    // skip expansion if there's no valid variables set. ex. {a} is the
    // first valid
    if (checkNotNull(template, "template").length() < 3)
      return template.toString();
    checkNotNull(variables, "variables for %s", template);

    boolean inVar = false;
    StringBuilder var = new StringBuilder();
    StringBuilder builder = new StringBuilder();
    for (char c : Lists.charactersOf(template)) {
      switch (c) {
        case '{':
          inVar = true;
          break;
        case '}':
          inVar = false;
          String key = var.toString();
          Object value = variables.get(var.toString());
          if (value != null)
            builder.append(value);
          else
            builder.append('{').append(key).append('}');   // 没有遇到一样的模板名称，则继续保留（报错容易马上发现）
          var = new StringBuilder();
          break;
        default:
          if (inVar)
            var.append(c);
          else
            builder.append(c);
      }
    }
    return builder.toString();
  }

  /* @see Request#method() */
  public RequestTemplate method(String method) {
    this.method = checkNotNull(method, "method");
    return this;
  }

  /* @see Request#method() */
  public String method() {
    return method;
  }

  /**
   * 在解析 Path注解的路径时，追加到url
   * 在{@code feign.Contract#parseAndValidatateMetadata(java.lang.reflect.Method)} 处理feign方法上的Path时获取
   * @see javax.ws.rs.Path
   * @see #url()
   */
  public RequestTemplate append(CharSequence value) {
    url.append(value);
    url = pullAnyQueriesOutOfUrl(url);
    return this;
  }

  /**
   * requestTemplate的请求url是拼接，
   * 这里暴露一个方法，让外部通过下标构造url
   *  @see #url()
   */
  public RequestTemplate insert(int pos, CharSequence value) {
    url.insert(pos, value);
    url = pullAnyQueriesOutOfUrl(url);  // 检查是否有查询参数
    return this;
  }

  public String url() {
    return url.toString();
  }

  /**
   * Replaces queries with the specified {@code configKey} with url decoded
   * {@code values} supplied.
   * <p/>
   * When the {@code value} is {@code null}, all queries with the {@code configKey}
   * are removed.
   * <p/>
   * <h4>relationship to JAXRS 2.0</h4>
   * <p/>
   * Like {@code WebTarget.query}, except the values can be templatized.
   * <p/>
   * ex.
   * <p/>
   * <pre>
   * template.query(&quot;Signature&quot;, &quot;{signature}&quot;);
   * </pre>
   *
   * @param configKey the configKey of the query
   * @param values    can be a single null to imply removing all values. Else no
   *                  values are expected to be null.
   * @see #queries()
   */
  public RequestTemplate query(String configKey, String... values) {
    queries.removeAll(checkNotNull(configKey, "configKey"));
    if (values != null && values.length > 0 && values[0] != null) {
      for (String value : values)
        this.queries.put(encodeIfNotVariable(configKey), encodeIfNotVariable(value));
    }
    return this;
  }

  /* @see #query(String, String...) */
  public RequestTemplate query(String configKey, Iterable<String> values) {
    if (values != null)
      return query(configKey, Iterables.toArray(values, String.class));
    return query(configKey, (String[]) null);
  }

  private String encodeIfNotVariable(String in) {
    if (in == null || in.indexOf('{') == 0)
      return in;
    return urlEncode(in);
  }

  /**
   * Replaces all existing queries with the newly supplied url decoded
   * queries.
   * <p/>
   * <h4>relationship to JAXRS 2.0</h4>
   * <p/>
   * Like {@code WebTarget.queries}, except the values can be templatized.
   * <p/>
   * ex.
   * <p/>
   * <pre>
   * template.queries(ImmutableMultimap.of(&quot;Signature&quot;, &quot;{signature}&quot;));
   * </pre>
   *
   * @param queries if null, remove all queries. else value to replace all queries
   *                with.
   * @see #queries()
   */
  public RequestTemplate queries(Multimap<String, String> queries) {
    if (queries == null || queries.isEmpty()) {
      this.queries.clear();
    } else {
      for (Entry<String, Collection<String>> entry : queries.asMap().entrySet())
        query(entry.getKey(), Iterables.toArray(entry.getValue(), String.class));
    }
    return this;
  }

  /**
   * Returns an immutable copy of the url decoded queries.
   *
   * @see Request#url()
   */
  public ListMultimap<String, String> queries() {
    ListMultimap<String, String> unencoded = LinkedListMultimap.create();
    for (Entry<String, String> entry : queries.entries())
      unencoded.put(urlDecode(entry.getKey()), urlDecode(entry.getValue()));
    return Multimaps.unmodifiableListMultimap(unencoded);
  }

  /**
   * 请求头，通过解析请求方法上的注解来获取
   * Replaces headers with the specified {@code configKey} with the
   * {@code values} supplied.
   * <p/>
   * When the {@code value} is {@code null}, all headers with the {@code configKey}
   * are removed.
   * <p/>
   * <h4>relationship to JAXRS 2.0</h4>
   * <p/>
   * Like {@code WebTarget.queries} and {@code javax.ws.rs.client.Invocation.Builder.header},
   * except the values can be templatized.
   * <p/>
   * ex.
   * <p/>
   * <pre>
   * template.query(&quot;X-Application-Version&quot;, &quot;{version}&quot;);
   * </pre>
   *
   * @param configKey the configKey of the header
   * @param values    can be a single null to imply removing all values. Else no
   *                  values are expected to be null.
   * @see #headers()
   */
  public RequestTemplate header(String configKey, String... values) {
    checkNotNull(configKey, "header configKey");
    if (values == null || (values.length == 1 && values[0] == null))
      headers.removeAll(configKey);
    else
      this.headers.replaceValues(configKey, ImmutableList.copyOf(values));
    return this;
  }

  /* @see #header(String, String...) */
  public RequestTemplate header(String configKey, Iterable<String> values) {
    if (values != null)
      return header(configKey, Iterables.toArray(values, String.class));
    return header(configKey, (String[]) null);
  }

  /**
   * Replaces all existing headers with the newly supplied headers.
   * <p/>
   * <h4>relationship to JAXRS 2.0</h4>
   * <p/>
   * Like {@code Invocation.Builder.headers(MultivaluedMap)}, except the
   * values can be templatized.
   * <p/>
   * ex.
   * <p/>
   * <pre>
   * template.headers(ImmutableMultimap.of(&quot;X-Application-Version&quot;, &quot;{version}&quot;));
   * </pre>
   *
   * @param headers if null, remove all headers. else value to replace all headers
   *                with.
   * @see #headers()
   */
  public RequestTemplate headers(Multimap<String, String> headers) {
    if (headers == null || headers.isEmpty())
      this.headers.clear();
    else
      this.headers.putAll(headers);
    return this;
  }

  /**
   * Returns an immutable copy of the current headers.
   *
   * @see Request#headers()
   */
  public ListMultimap<String, String> headers() {
    return ImmutableListMultimap.copyOf(headers);
  }

  /**
   * replaces the {@link com.google.common.net.HttpHeaders#CONTENT_LENGTH} header.
   * <p/>
   * Usually populated by {@link feign.codec.BodyEncoder} or {@link feign.codec.FormEncoder}
   *
   * @see Request#body()
   */
  public RequestTemplate body(String body) {
    this.body = Optional.fromNullable(body);
    if (this.body.isPresent()) {
      byte[] contentLength = body.getBytes(UTF_8);
      header(CONTENT_LENGTH, String.valueOf(contentLength.length));
    }
    this.bodyTemplate = Optional.absent();
    return this;
  }

  /* @see Request#body() */
  public Optional<String> body() {
    return body;
  }

  /**
   * populated by {@link Body}
   *
   * @see Request#body()
   */
  public RequestTemplate bodyTemplate(String bodyTemplate) {
    this.bodyTemplate = Optional.fromNullable(bodyTemplate);
    this.body = Optional.absent();
    return this;
  }

  /**
   * @see Request#body()
   * @see #expand(String, Map)
   */
  public Optional<String> bodyTemplate() {
    return bodyTemplate;
  }

  @Override public int hashCode() {
    return Objects.hashCode(method, url, queries, headers, body);
  }

  /**
   * 将body的查询参数提取出来, 并添加到queries
   * 并返回纯url(无查询参数
   * if there are any query params in the {@link #body()}, this will extract
   * them out.
   *
   * @return
   */
  private StringBuilder pullAnyQueriesOutOfUrl(StringBuilder url) {
    // parse out queries
    int queryIndex = url.indexOf("?");
    if (queryIndex != -1) {
      String queryLine = url.substring(queryIndex + 1);
      ListMultimap<String, String> firstQueries = parseAndDecodeQueries(queryLine);
      if (!queries.isEmpty()) { // 如果不为空，则合并 （优先queries的参数
        firstQueries.putAll(queries);
        queries.clear();
      }
      queries.putAll(firstQueries);
      return new StringBuilder(url.substring(0, queryIndex));
    }
    return url;
  }

  /**
   * 将查询参数
   * @param queryLine
   * @return
   */
  private static ListMultimap<String, String> parseAndDecodeQueries(String queryLine) {
    ListMultimap<String, String> map = LinkedListMultimap.create();
    if (Strings.emptyToNull(queryLine) == null)
      return map;
    // 将查询串编解码
    if (queryLine.indexOf('&') == -1) {
      // 只有一对kv
      if (queryLine.indexOf('=') != -1)
        putKV(queryLine, map);      // 解析ky，保存到map
      else
        map.put(queryLine, null);   // 只有key,没有value
    } else {
      for (String part : Splitter.on('&').split(queryLine)) {  // 解析多个ky
        putKV(part, map);
      }
    }
    return map;
  }

  /**
   * 解析 stringToParse 到 map (k=v格式)
   * @param stringToParse
   * @param map
   */
  private static void putKV(String stringToParse, Multimap<String, String> map) {
    String key;
    String value;
    // note that '=' can be a valid part of the value
    int firstEq = stringToParse.indexOf('=');
    if (firstEq == -1) {
      key = urlDecode(stringToParse);
      value = null;
    } else {
      key = urlDecode(stringToParse.substring(0, firstEq));
      value = urlDecode(stringToParse.substring(firstEq + 1));
    }
    map.put(key, value);
  }

  @Override public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (RequestTemplate.class != obj.getClass())
      return false;
    RequestTemplate that = RequestTemplate.class.cast(obj);
    return equal(this.method, that.method) && equal(this.url, that.url) && equal(this.queries, that.queries)
        && equal(this.headers, that.headers) && equal(this.body, that.body);
  }

  @Override public String toString() {
    return request().toString();
  }

  /**
   * 构造查询串
   * @return
   */
  public String queryLine() {
    if (queries.isEmpty())
      return "";
    StringBuilder queryBuilder = new StringBuilder();
    for (Entry<String, ?> pair : queries.entries()) {
      queryBuilder.append('&');
      queryBuilder.append(pair.getKey());
      if (pair.getValue() != null)
        queryBuilder.append('=');
      if (pair.getValue() != null && !pair.getValue().equals("")) {
        queryBuilder.append(pair.getValue());
      }
    }
    queryBuilder.deleteCharAt(0);  // 删除第一个&
    return queryBuilder.insert(0, '?').toString();
  }

  private static final long serialVersionUID = 1L;
}
