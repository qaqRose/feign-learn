package feign.example;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import java.net.URI;
import java.util.List;

/**
 * @author qiuxq
 * @date 2023/6/30
 */
public interface GitHub {

    @GET
    @Path("/repos/{owner}/{repo}/contributors")
    List<Contributor> contributors(@PathParam("owner") String owner, @PathParam("repo") String repo);


    @GET
    @Path("/repos/{owner}/{repo}/contributors")
    List<Contributor> contributors2(URI uri, @PathParam("owner") String owner, @PathParam("repo") String repo);


}
