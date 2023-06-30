package feign.example.api;

import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import dagger.Module;
import dagger.Provides;
import feign.Feign;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.codec.Decoder;
import feign.codec.FormEncoder;
import feign.example.Api4T;

import javax.inject.Singleton;
import java.io.Reader;
import java.util.Map;

/**
 * @author qiuxq
 * @date 2023/6/30
 */
public class ApiExample {

    public static void main(String... args) {
        Api4T api = Feign.create(Api4T.class, "api", new FormModule());

        // Fetch and print a list of the contributors to this library.
        Response test = api.test("name");

        System.out.println(test.toString());
    }


    @Module(overrides = true, library = true)
    static class FormModule {

        @Provides
        @Singleton
        Map<String, FormEncoder> formEncoders() {
            return ImmutableMap.of("Api4T", formEncoder);
        }
        final FormEncoder formEncoder = new FormEncoder() {
            public void encodeForm(Map<String, ?> formParams, RequestTemplate base) {
                // form 表单注入
                System.out.println(new Gson().toJson(formParams));
                System.out.println("encodeForm");
            }
        };

        @Provides
        @Singleton
        Map<String, Request.Options> options() {
            return ImmutableMap.of("Api4T", new Request.Options(100, 200));
        }

    }


    @Module(overrides = true, library = true)
    static class GsonModule {
        @Provides
        @Singleton
        Map<String, Decoder> decoders() {
            return ImmutableMap.of("Api4T", jsonDecoder);
        }

        final Decoder jsonDecoder = new Decoder() {
            Gson gson = new Gson();

            @Override public Object decode(String methodKey, Reader reader, TypeToken<?> type) {
                return gson.fromJson(reader, type.getType());
            }
        };
    }
}
