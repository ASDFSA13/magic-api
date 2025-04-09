<template>
	<div class="magic-script-editor">
		<div class="magic-empty-container" v-if="openedScripts.length === 0">
			<div class="magic-hot-key">
				<p>
				{{ $i('message.save') }}<em>Ctrl + S</em><br/>
				{{ $i('message.run') }}<em>Ctrl + Q</em><br/>
				{{ $i('editor.tooltip.complection')}}<em>Alt + /</em><br/>
				{{ $i('editor.tooltip.resume')}}<em>F8</em><br/>
				{{ $i('editor.tooltip.stepInto')}}<em>F6</em><br/>
				{{ $i('editor.tooltip.format')}}<em>Ctrl + Alt + L</em><br/>
				{{ $i('editor.tooltip.recent')}}<em>Ctrl + E</em>
				</p>
			</div>
		</div>
		<template v-else>
			<magic-tab v-model:value="selectTab" :tabs="openedScripts" className="magic-script-tab"  ref="tab"
				:allow-close="true" @close="onClose" @change="tab => bus.$emit(Message.OPEN, tab)"
				@before-change="beforeChange" @item-contextmenu="onContextMenu">
				<template v-slot="{ tab }">
					<magic-text-icon :icon="tab.getIcon(tab.item)"/>{{ tab.item.name }}<span v-if="isUpdated(tab)">*</span>
					<magic-icon v-if="tab.item.lock === $LOCKED" icon="lock"/>
					<magic-avatar-group :users="activateUserFiles[tab.item.id] || []" :max="3" :size="20"/>
				</template>
			</magic-tab>
			<magic-loading :loading="loading">
				<div class="magic-monaco-editor-wrapper">
					<component :is="selectTab.component" :Message="Message" :bus="bus" :request="request" :selectTab="selectTab" v-if="selectTab.pageType=='component'" />
					<magic-monaco-editor ref="editor" v-else v-model:value="selectTab.item.script" v-model:decorations="selectTab.decorations" :language="selectTab.language" :support-breakpoint="true"/>
				</div>
			</magic-loading>
		</template>
	</div>
</template>
<script setup>
import { getCurrentInstance, inject, nextTick, onMounted, reactive, ref, toRaw, watch } from 'vue'
import bus from '../../../scripts/bus.js'
import request from '../../../scripts/request.js'
import constants from '../../../scripts/constants.js'
import Message from '../../../scripts/constants/message.js'
import { Range } from 'monaco-editor'
import { convertVariables } from '../../../scripts/utils.js'
import RequestParameter from '../../../scripts/editor/request-parameter.js'
import Socket from '../../../scripts/constants/socket.js'
import store from '../../../scripts/store.js'
import $i from '../../../scripts/i18n.js'
const { proxy } = getCurrentInstance()
const openedScripts = reactive([])
const selectTab = ref({})
const loading = ref(true)
const editor = ref(null)
const tab = ref(null)
const activateUserFiles = inject('activateUserFiles')
const javaTypes = {
	'String': 'java.lang.String',
	'Integer': 'java.lang.Integer',
	'Double': 'java.lang.Double',
	'Long': 'java.lang.Long',
	'Byte': 'java.lang.Byte',
	'Short': 'java.lang.Short',
	'Float': 'java.lang.Float',
	'MultipartFile': 'org.springframework.web.multipart.MultipartFile',
	'MultipartFiles': 'java.util.List',
}
RequestParameter.setEnvironment(() => {
	const env = {}
	const item = selectTab.value?.item
	const append = array => array && Array.isArray(array) && array.forEach(it => {
		if(it && typeof it.name === 'string' && it.dataType){
			env[it.name] = javaTypes[it.dataType] || 'java.lang.Object'
		}
	})
	if(item){
		append(item?.parameters)
		append(item?.paths)
	}
	return env
})
// 关闭tab
const onClose = tab => {
	let index = openedScripts.findIndex(it => it === tab)
	openedScripts.splice(index, 1)
	if(tab === selectTab.value){
		let len = openedScripts.length
		if(index < len){
			bus.$emit(Message.OPEN, openedScripts[index])
		} else if(len > 0) {
			bus.$emit(Message.OPEN, openedScripts[index - 1])
		}
	}
	bus.$emit(Message.CLOSE, tab.item)
	// 没有打开的文件时
	if(openedScripts.length === 0){
		// 设置标题为空
		bus.$emit(Message.OPEN_EMPTY)
		selectTab.value = {}
	}
}
watch(openedScripts, (newVal) => {
	store.set(constants.RECENT_OPENED_TAB, newVal.filter(it => it.item?.id).map(it => it.item.id))
})
// 执行保存
const doSave = (flag) => {
	const opened = selectTab.value
	if(opened && opened.item){
		const item = selectTab.value.processSave(opened.item)
		Object.keys(item).forEach(key => opened.item[key] = item[key])
		return request.sendJson(`/resource/file/${selectTab.value.type}/save?auto=${flag ? 0 : 1}`, item).success((id) => {
			const msg = `${opened.title}「${opened.path()}」`
			if(id) {
				bus.status('message.saveSuccess', true, msg)
				opened.tmpObject = JSON.parse(JSON.stringify(item))
				if(opened.item.id !== id){
					bus.loading(1)
				}
				opened.item.id = id
			}else{
				bus.status('message.saveFailed', false, msg)
				proxy.$alert($i('message.saveFailed', msg))
			}
		})
	}
}
// 执行测试
const doTest = () => selectTab.value.doTest(selectTab.value)
const doContinue = step => {
    if(selectTab.value.debuging){
        editor.value.removedDecorations(selectTab.value.debugDecorations)
        selectTab.value.debuging = false
        selectTab.value.variables = null
        const breakpoints = (selectTab.value.decorations || []).filter(it => it.options.linesDecorationsClassName === 'breakpoints').map(it => it.range.startLineNumber).join('|')
        bus.send(Socket.RESUME_BREAKPOINT, [selectTab.value.item.id, (step === true ? '1' : '0'), breakpoints].join(','))
    }
}
// tab 右键菜单事件
const onContextMenu = (event, item, index) => {
	const menus = [{
		label: $i('editor.tab.close'),
		divided: true,
		onClick(){
			onClose(item)
		}
	},{
		label: $i('editor.tab.closeOther'),
		divided: true,
		onClick(){
			[...openedScripts].forEach((it, i) => i != index && onClose(it))
		}
	},{
		label: $i('editor.tab.closeLeft'),
		onClick(){
			[...openedScripts].forEach((it, i) => i < index && onClose(it))
		}
	},{
		label: $i('editor.tab.closeRight'),
		divided: true,
		onClick(){
			[...openedScripts].forEach((it, i) => i > index && onClose(it))
		}
	},{
		label: $i('editor.tab.closeAll'),
		onClick(){
			[...openedScripts].forEach(it => onClose(it))
		}
	}]
	constants.PLUGINS.forEach(it => {
		if(it.contextmenu && typeof it.contextmenu === 'function'){
			const pMenus = it.contextmenu({
				...item,
				menuType: 'editorTab'
			})
			pMenus && pMenus.length && pMenus.forEach(m => menus.push(m))
		}
	})
	proxy.$contextmenu({ menus, event})
}
// 判断是否有变动
const isUpdated = (tab) => Object.keys(tab.tmpObject || {}).some(k => {
	const v1 = tab.tmpObject[k]
	const v2 = tab.item[k]
	if(v1 === v2 || k === 'properties' || k === 'responseBody' || k === 'responseBodyDefinition'){
		return false
	}
	return (typeof v1 === 'object' || typeof v2 === 'object') ? JSON.stringify(v1) !== JSON.stringify(v2) : v1 !== v2
})
// 退出登录
bus.$on(Message.LOGOUT, () => [...openedScripts].forEach(tab => onClose(tab)))
// 执行删除时
bus.$on(Message.DELETE_FILE, item => {
	const index = openedScripts.findIndex(it => it.item === item)
	if(index > -1){
		onClose(openedScripts[index])
	}
})
// 重新加载资源完毕时
bus.$on(Message.RELOAD_RESOURCES_FINISH, ()=> [...openedScripts].forEach(it => onClose(it)))
// 登录响应
bus.$event(Socket.LOGIN_RESPONSE, () => {
	if(selectTab.value){
		bus.send(Socket.SET_FILE_ID, selectTab.value.item?.id || '0')
	}
})
const beforeChange = tab => {
	if(tab && editor.value){
		tab.scrollTop = editor.value.getScrollTop()
	}
}
// 打开文件
bus.$on(Message.OPEN, opened => {
	let find = openedScripts.find(it => it.item === opened.item || (it.item.id && it.item.id === opened.item.id))
	bus.send(Socket.SET_FILE_ID, opened.item.id || '0')
	if(find){
		selectTab.value = find
		loading.value = false
		nextTick(() => editor.value.setScrollTop(find.scrollTop || 0))
	} else {
		openedScripts.push(opened)
		selectTab.value = opened
		if(opened.item.id && !opened.item.script){
			loading.value = true
			request.sendGet(`/resource/file/${opened.item.id}`).success(data => {
				bus.status('message.getDetail', true, `${opened.title}「${opened.path()}」`)
				Object.keys(data).forEach(key => opened.item[key] = data[key])
				opened.tmpObject = JSON.parse(JSON.stringify(opened.processSave(data)))
				loading.value = false
				nextTick(() => editor.value.setScrollTop(0))
			})
		} else {
			opened.tmpObject = JSON.parse(JSON.stringify(opened.processSave(opened.item)))
			loading.value = false
			nextTick(() => editor.value.setScrollTop(0))
		}
	}
	if(selectTab.value.decorations && selectTab.value.decorations.length > 0){
		nextTick(() => {
			const decorations = toRaw(selectTab.value.decorations)
			selectTab.value.debugDecorations = editor.value.appendDecoration(decorations)
												.map((it, index) => decorations[index].options?.className === 'debug-line' ? it : null)
												.filter(it => it !== null) || []
		})
	}
	nextTick(() => tab.value && tab.value.scrollIntoView(opened))
})
// 保存事件
bus.$on(Message.DO_SAVE, doSave)
// 测试事件
bus.$on(Message.DO_TEST, () => {
	const opened = selectTab.value
	if(opened && opened.item && opened.runnable && opened.doTest && opened.running !== true){
		if(constants.AUTO_SAVE && opened.item.lock !== '1'){
			doSave().end(successed => successed && doTest())
		} else {
			doTest()
		}
	}
})
// 进入断点
bus.$event(Socket.BREAKPOINT, ([ scriptId, { range, variables } ]) => {
    // 如果切换或已关闭
    if(selectTab.value?.item?.id !== scriptId){
        const opened = openedScripts.find(it => it.item.id === scriptId)
        if(opened){
            // 切换tab
            bus.$emit(Message.OPEN, opened)
        }else{
            // TODO  重新打开
        }
    }
    nextTick(() => {
        selectTab.value.variables = convertVariables(variables)
        selectTab.value.debuging = true
        selectTab.value.debugDecorations = [editor.value.appendDecoration([{
            range: new Range(range[0], 1, range[0], 1),
            options: {
                isWholeLine: true,
                inlineClassName: 'debug-line',
                className: 'debug-line'
            }
        }])]
        bus.$emit(Message.SWITCH_TOOLBAR, 'debug')
    })
})
// 恢复断点
bus.$on(Message.DEBUG_CONTINUE, doContinue)
// 断点单步运行
bus.$on(Message.DEBUG_SETPINTO, () => doContinue(true))
// 执行出现异常
bus.$event(Socket.EXCEPTION, ([ [scriptId, message, line] ]) => {
	if(selectTab.value?.item?.id === scriptId){
		const range = new Range(line[0], line[2], line[1], line[3] + 1)
		const instance = editor.value.getInstance()
		const decorations = instance.deltaDecorations([], [{
			range,
			options: {
				hoverMessage: {
					value: message
				},
				inlineClassName: 'squiggly-error'
			}
		}])
		instance.revealRangeInCenter(range)
		instance.focus()
		if(constants.DECORATION_TIMEOUT >= 0){
			setTimeout(() => instance.deltaDecorations(decorations, []), constants.DECORATION_TIMEOUT)
		}
	}
})
const emit = defineEmits(['onLoad'])
onMounted(() => emit('onLoad'))
</script>
<style scoped>
.magic-script-editor{
	flex: 1;
	overflow: hidden;
	position: relative;
}
.magic-script-editor .magic-monaco-editor-wrapper{
    position: absolute;
	top: 30px;
	left: 0;
	right: 0;
	bottom: 0;
}
.magic-empty-container{
	flex: 1;
	position: relative;
	width: 100%;
	height: 100%;
	background: var(--empty-background-color);


  background-image: url('data:image/png;base64,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');  /* 替换为你的实际图片路径 */
  background-repeat: no-repeat;          /* 不重复显示 */
  background-position: center center;    /* 居中显示 */
  background-size: contain;              /* 保持图片完整显示 */
}
.magic-hot-key{
	position: absolute;
	top: 50%;
	margin-top: -105px;
	text-align: center;
	color: var(--empty-color);
	font-size: 16px;
	width: 100%;
}
.magic-hot-key p{
	display: inline-block;
	text-align: left;
	line-height: 30px;
}
.magic-hot-key p em{
	margin-left: 15px;
	font-style: normal;
	color: var(--empty-key-color);
}
.magic-monaco-editor{
	position: absolute;
	top: 0;
	bottom: 0;
	left:0;
	right: 0;
	overflow: visible !important;
}
.magic-script-editor :deep(.magic-avatar-group){
	margin-left: 10px;
}
</style>
