package io.pisceshub.muchat.server.service.impl;

import com.alibaba.fastjson.JSON;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.pisceshub.muchat.common.core.contant.RedisKey;
import io.pisceshub.muchat.server.contant.Constant;
import io.pisceshub.muchat.server.common.entity.Friend;
import io.pisceshub.muchat.server.common.entity.GroupMember;
import io.pisceshub.muchat.server.common.entity.User;
import io.pisceshub.muchat.server.exception.GlobalException;
import io.pisceshub.muchat.server.mapper.UserMapper;
import io.pisceshub.muchat.server.service.IFriendService;
import io.pisceshub.muchat.server.service.IGroupMemberService;
import io.pisceshub.muchat.server.service.IUserService;
import io.pisceshub.muchat.server.util.SessionContext;
import io.pisceshub.muchat.server.util.BeanUtils;
import io.pisceshub.muchat.server.util.JwtUtil;
import io.pisceshub.muchat.common.core.enums.ResultCode;
import io.pisceshub.muchat.server.common.vo.user.LoginReq;
import io.pisceshub.muchat.server.common.vo.user.RegisterReq;
import io.pisceshub.muchat.server.common.vo.user.LoginResp;
import io.pisceshub.muchat.server.common.vo.user.UserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;


@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    RedisTemplate<String,Object> redisTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private IGroupMemberService groupMemberService;

    @Autowired
    private IFriendService friendService;

    /**
     * 用户登录
     *
     * @param dto 登录dto
     * @return
     */

    @Override
    public LoginResp login(LoginReq dto) {
        User user = findUserByName(dto.getUserName());
        if(null == user){
            throw  new GlobalException(ResultCode.PROGRAM_ERROR,"用户不存在");
        }
        if(!passwordEncoder.matches(dto.getPassword(),user.getPassword())){
            throw  new GlobalException(ResultCode.PASSWOR_ERROR);
        }
        // 生成token
        SessionContext.UserSessionInfo session = BeanUtils.copyProperties(user, SessionContext.UserSessionInfo.class);
        String strJson = JSON.toJSONString(session);
        String accessToken = JwtUtil.sign(user.getId(),strJson, Constant.ACCESS_TOKEN_EXPIRE,Constant.ACCESS_TOKEN_SECRET);
        String refreshToken = JwtUtil.sign(user.getId(),strJson, Constant.REFRESH_TOKEN_EXPIRE, Constant.REFRESH_TOKEN_SECRET);
        LoginResp vo = new LoginResp();
        vo.setAccessToken(accessToken);
        vo.setAccessTokenExpiresIn(Constant.ACCESS_TOKEN_EXPIRE);
        vo.setRefreshToken(refreshToken);
        vo.setRefreshTokenExpiresIn(Constant.REFRESH_TOKEN_EXPIRE);
        return vo;
    }

    /**
     * 用refreshToken换取新 token
     *
     * @param refreshToken
     * @return
     */
    @Override
    public LoginResp refreshToken(String refreshToken) {
        try{
            //验证 token
            JwtUtil.checkSign(refreshToken, Constant.REFRESH_TOKEN_SECRET);
            String strJson = JwtUtil.getInfo(refreshToken);
            Long userId = JwtUtil.getUserId(refreshToken);
            String accessToken = JwtUtil.sign(userId,strJson, Constant.ACCESS_TOKEN_EXPIRE,Constant.ACCESS_TOKEN_SECRET);
            String newRefreshToken = JwtUtil.sign(userId,strJson, Constant.REFRESH_TOKEN_EXPIRE, Constant.REFRESH_TOKEN_SECRET);
            LoginResp vo =new LoginResp();
            vo.setAccessToken(accessToken);
            vo.setAccessTokenExpiresIn(Constant.ACCESS_TOKEN_EXPIRE);
            vo.setRefreshToken(newRefreshToken);
            vo.setRefreshTokenExpiresIn(Constant.REFRESH_TOKEN_EXPIRE);
            return vo;
        }catch (JWTVerificationException e) {
            throw new GlobalException("refreshToken已失效");
        }
    }

    /**
     * 用户注册
     *
     * @param dto 注册dto
     * @return
     */
    @Override
    public void register(RegisterReq dto) {
        User user = findUserByName(dto.getUserName());
        if(null != user){
            throw  new GlobalException(ResultCode.USERNAME_ALREADY_REGISTER);
        }
        user = BeanUtils.copyProperties(dto,User.class);
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setSignature("我就是我，不一样的烟火");
        this.save(user);
        log.info("注册用户，用户id:{},用户名:{},昵称:{}",user.getId(),dto.getUserName(),dto.getNickName());
    }

    /**
     * 根据用户名查询用户
     *
     * @param username 用户名
     * @return
     */
    @Override
    public User findUserByName(String username) {
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(User::getUserName,username);
        return this.getOne(queryWrapper);
    }

    /**
     * 更新用户信息，好友昵称和群聊昵称等冗余信息也会更新
     *
     * @param vo 用户信息vo
     * @return
     */
    @Transactional
    @Override
    public void update(UserVO vo) {
        SessionContext.UserSessionInfo session = SessionContext.getSession();
        if(!session.getId().equals(vo.getId()) ){
            throw  new GlobalException(ResultCode.PROGRAM_ERROR,"不允许修改其他用户的信息!");
        }
        User user = this.getById(vo.getId());
        if(null == user){
            throw  new GlobalException(ResultCode.PROGRAM_ERROR,"用户不存在");
        }
        // 更新好友昵称和头像
        if(!user.getNickName().equals(vo.getNickName()) || !user.getHeadImageThumb().equals(vo.getHeadImageThumb())){
            QueryWrapper<Friend> queryWrapper = new QueryWrapper<>();
            queryWrapper.lambda().eq(Friend::getFriendId,session.getId());
            List<Friend> friends = friendService.list(queryWrapper);
            for(Friend friend: friends){
                friend.setFriendNickName(vo.getNickName());
                friend.setFriendHeadImage(vo.getHeadImageThumb());
            }
            friendService.updateBatchById(friends);
        }
        // 更新群聊中的头像
        if(!user.getHeadImageThumb().equals(vo.getHeadImageThumb())){
            List<GroupMember> members = groupMemberService.findByUserId(session.getId());
            for(GroupMember member:members){
                member.setHeadImage(vo.getHeadImageThumb());
            }
            groupMemberService.updateBatchById(members);
        }
        // 更新用户信息
        user.setNickName(vo.getNickName());
        user.setSex(vo.getSex());
        user.setSignature(vo.getSignature());
        user.setHeadImage(vo.getHeadImage());
        user.setHeadImageThumb(vo.getHeadImageThumb());
        this.updateById(user);
        log.info("用户信息更新，用户:{}}",user.toString());
    }


    /**
     * 根据用户昵称查询用户，最多返回20条数据
     *
     * @param nickname 用户昵称
     * @return
     */
    @Override
    public List<UserVO> findUserByNickName(String nickname) {
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda()
                .like(User::getNickName,nickname)
                .last("limit 20");
        List<User> users = this.list(queryWrapper);
        List<UserVO> vos = users.stream().map(u-> {
            UserVO vo = BeanUtils.copyProperties(u,UserVO.class);
            vo.setOnline(isOnline(u.getId()));
            return vo;
        }).collect(Collectors.toList());
        return vos;
    }


    /**
     * 判断用户是否在线，返回在线的用户id列表
     *
     * @param userIds 用户id，多个用‘,’分割
     * @return
     */
    @Override
    public List<Long> checkOnline(String userIds) {
        String[] idArr = userIds.split(",");
        List<Long> onlineIds = new LinkedList<>();
        for(String userId:idArr){
           if(isOnline(Long.parseLong(userId))){
                onlineIds.add(Long.parseLong(userId));
            }
        }
        return onlineIds;
    }

    @Override
    public UserVO findByUserIdAndFriendId(Long userId, Long friendId) {
        if(!friendService.isFriend(userId,friendId)){
            return null;
        }
        User user = getById(userId);
        if(user==null){
            return null;
        }
        return BeanUtils.copyProperties(user,UserVO.class);
    }


    private boolean isOnline(Long userId){
        String key = RedisKey.IM_USER_SERVER_ID + userId;
        Integer serverId = (Integer) redisTemplate.opsForValue().get(key);
        return serverId!=null && serverId>=0;
    }
}
