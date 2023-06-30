package feign.example.url;

import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import dagger.Module;
import dagger.Provides;
import feign.Feign;
import feign.codec.Decoder;
import feign.example.Contributor;
import feign.example.GitHub;

import javax.inject.Singleton;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

/**
 * @author qiuxq
 * @date 2023/6/30
 */
public class GitHubUrlExample {

    public static void main(String... args) throws URISyntaxException {

        GitHub github = Feign.create(GitHub.class, "https://api.github.com", new GsonModule());

        // 有限方法级别的http连接
        URI uri = new URI("https://api.github.com");
        List<Contributor> contributors = github.contributors2(uri, "netflix", "feign");
        for (Contributor contributor : contributors) {
            System.out.println(contributor.login + " (" + contributor.contributions + ")");
        }
    }


    @Module(overrides = true, library = true)
    static class GsonModule {
        @Provides
        @Singleton
        Map<String, Decoder> decoders() {
            return ImmutableMap.of("GitHub", jsonDecoder);
        }

        final Decoder jsonDecoder = new Decoder() {
            Gson gson = new Gson();

            @Override
            public Object decode(String methodKey, Reader reader, TypeToken<?> type) {
                return gson.fromJson(reader, type.getType());
            }
        };
    }
}
