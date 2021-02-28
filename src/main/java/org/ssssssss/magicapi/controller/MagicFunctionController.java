package org.ssssssss.magicapi.controller;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.ssssssss.magicapi.config.MagicConfiguration;
import org.ssssssss.magicapi.interceptor.RequestInterceptor;
import org.ssssssss.magicapi.model.FunctionInfo;
import org.ssssssss.magicapi.model.JsonBean;
import org.ssssssss.magicapi.provider.FunctionServiceProvider;
import org.ssssssss.magicapi.utils.IoUtils;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

public class MagicFunctionController extends MagicController {

	private FunctionServiceProvider functionService;

	private static final Logger logger = LoggerFactory.getLogger(MagicFunctionController.class);

	public MagicFunctionController(MagicConfiguration configuration) {
		super(configuration);
		this.functionService = configuration.getFunctionServiceProvider();
	}

	@RequestMapping("/function/list")
	@ResponseBody
	public JsonBean<List<FunctionInfo>> list() {
		try {
			return new JsonBean<>(functionService.list());
		} catch (Exception e) {
			logger.error("查询函数列表失败", e);
			return new JsonBean<>(-1, e.getMessage());
		}
	}

	@RequestMapping("/function/get")
	@ResponseBody
	public JsonBean<FunctionInfo> get(String id, HttpServletRequest request) {
		if (!allowVisit(request, RequestInterceptor.Authorization.DETAIL)) {
			return new JsonBean<>(-10, "无权限执行查看详情方法");
		}
		try {
			return new JsonBean<>(functionService.get(id));
		} catch (Exception e) {
			logger.error("查询函数出错", e);
			return new JsonBean<>(-1, e.getMessage());
		}
	}

	@RequestMapping("/function/backup/get")
	@ResponseBody
	public JsonBean<FunctionInfo> backups(String id, Long timestamp) {
		return new JsonBean<>(functionService.backupInfo(id, timestamp));
	}

	@RequestMapping("/function/backups")
	@ResponseBody
	public JsonBean<List<Long>> backups(String id) {
		return new JsonBean<>(functionService.backupList(id));
	}

	@RequestMapping("/function/move")
	@ResponseBody
	public JsonBean<Boolean> move(String id, String groupId, HttpServletRequest request) {
		if(configuration.getWorkspace().readonly()){
			return new JsonBean<>(0, "当前为只读模式,无法移动");
		}
		if (!allowVisit(request, RequestInterceptor.Authorization.SAVE)) {
			return new JsonBean<>(-10, "无权限执行移动函数");
		}
		try {
			if (!functionService.allowMove(id, groupId)) {
				return new JsonBean<>(0, "移动后名称会重复，请修改名称后在试。");
			}
			if (!configuration.getMagicFunctionManager().move(id, groupId)) {
				return new JsonBean<>(0, "该路径已被映射,请换一个路径");
			} else {
				return new JsonBean<>(functionService.move(id, groupId));
			}
		} catch (Exception e) {
			logger.error("移动函数出错", e);
			return new JsonBean<>(-1, e.getMessage());
		}
	}


	@RequestMapping("/function/save")
	@ResponseBody
	public JsonBean<String> save(FunctionInfo functionInfo, HttpServletRequest request) {
		if(configuration.getWorkspace().readonly()){
			return new JsonBean<>(0, "当前为只读模式,无法保存");
		}
		if (!allowVisit(request, RequestInterceptor.Authorization.SAVE)) {
			return new JsonBean<>(-10, "无权限执行保存方法");
		}
		if (StringUtils.isBlank(functionInfo.getName())) {
			return new JsonBean<>(0, "函数名称不能为空");
		}
		if (!IoUtils.validateFileName(functionInfo.getName())) {
			return new JsonBean<>(0, "函数名称不能包含特殊字符，只允许中文、数字、字母以及_组合");
		}
		if (StringUtils.isBlank(functionInfo.getPath())) {
			return new JsonBean<>(0, "函数路径不能为空");
		}
		if (StringUtils.isBlank(functionInfo.getScript())) {
			return new JsonBean<>(0, "脚本内容不能为空");
		}
		if (configuration.getMagicFunctionManager().hasRegister(functionInfo)) {
			return new JsonBean<>(0, "该路径已被映射,请换一个路径");
		}
		try {
			if (StringUtils.isBlank(functionInfo.getId())) {
				if (functionService.exists(functionInfo.getName(), functionInfo.getPath(), functionInfo.getGroupId())) {
					return new JsonBean<>(0, String.format("函数%s已存在或名称重复", functionInfo.getPath()));
				}
				if(!functionService.insert(functionInfo)){
					return new JsonBean<>(0, "保存失败,请检查函数名称是否重复且不能包含特殊字符。");
				}
			} else {
				if (functionService.existsWithoutId(functionInfo.getName(), functionInfo.getPath(), functionInfo.getGroupId(), functionInfo.getId())) {
					return new JsonBean<>(0, String.format("函数%s已存在或名称重复", functionInfo.getPath()));
				}
				if(!functionService.update(functionInfo)){
					return new JsonBean<>(0, "保存失败,请检查函数名称是否重复且不能包含特殊字符。");
				}
				functionService.backup(functionInfo);
			}
			configuration.getMagicFunctionManager().register(functionInfo);
			return new JsonBean<>(functionInfo.getId());
		} catch (Exception e) {
			logger.error("保存函数出错", e);
			return new JsonBean<>(-1, e.getMessage());
		}
	}

	@RequestMapping("/function/delete")
	@ResponseBody
	public JsonBean<Boolean> delete(String id, HttpServletRequest request) {
		if(configuration.getWorkspace().readonly()){
			return new JsonBean<>(0, "当前为只读模式,无法删除");
		}
		if (!allowVisit(request, RequestInterceptor.Authorization.DELETE)) {
			return new JsonBean<>(-10, "无权限执行删除方法");
		}
		try {
			boolean success = functionService.delete(id);
			if (success) {
				configuration.getMagicFunctionManager().unregister(id);
			}
			return new JsonBean<>(success);
		} catch (Exception e) {
			logger.error("删除函数出错", e);
			return new JsonBean<>(-1, e.getMessage());
		}
	}
}
