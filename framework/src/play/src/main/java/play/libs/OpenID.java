package play.libs;

import scala.runtime.AbstractFunction1;

import play.api.libs.concurrent.Promise;

import play.libs.F;
import play.mvc.Http;
import play.mvc.Http.Request;


public class OpenID {

    /**
     * Retrieve the URL where the user should be redirected to start the OpenID authentication process
     */
    public static F.Promise<String> redirectURL(String openID, String callbackURL) {
        return new F.Promise<String>(play.api.libs.openid.OpenID.redirectURL(openID, callbackURL));
    }

    /**
     * Check the identity of the user from the current request, that should be the callback from the OpenID server
     */
    public static F.Promise<UserInfo> verifiedId() {
        Request request = Http.Context.current().request();
        Promise<UserInfo> scalaPromise = play.api.libs.openid.OpenID.verifiedId(request.queryString()).map(
                new AbstractFunction1<play.api.libs.openid.UserInfo, UserInfo>() {
                    @Override
                    public UserInfo apply(play.api.libs.openid.UserInfo scalaUserInfo) {
                        return new UserInfo(scalaUserInfo.id());
                    }
                });
        return new F.Promise<UserInfo>(scalaPromise);
    }

    public static class UserInfo {
        public String id;
        public UserInfo(String id) {
            this.id = id;
        }
    }

}