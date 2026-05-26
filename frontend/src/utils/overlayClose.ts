/** 全站弹层：禁止遮罩与 Esc 关闭；仅标题栏 X 或弹窗内业务按钮（取消/确定等） */
export const OVERLAY_CLOSE_PROPS = {
  maskClosable: false,
  keyboard: false,
} as const

export function installOverlayEscBlock(): void {
  document.addEventListener(
    'keydown',
    (e) => {
      if (e.key !== 'Escape') return
      if (!document.querySelector('.ant-drawer-open, .ant-modal-open')) return
      e.stopImmediatePropagation()
      e.preventDefault()
    },
    true,
  )
}
