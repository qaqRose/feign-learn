package feign.example;

import feign.Response;

import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;

/**
 * @author qiuxq
 * @date 2023/6/30
 */
public interface Api4T {


    @POST
    @Path("http://api.com/test")
    Response test(@FormParam("name") String name);


}
