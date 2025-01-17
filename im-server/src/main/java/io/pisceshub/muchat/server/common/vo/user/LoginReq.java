package io.pisceshub.muchat.server.common.vo.user;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;


@Data
@ApiModel("用户登录VO")
public class LoginReq {

    //@NotEmpty(message="用户名不可为空")
    @ApiModelProperty(value = "用户名")
    private String userName;

   // @NotEmpty(message="用户密码不可为空")
    @ApiModelProperty(value = "用户密码")
    private String password;

}
