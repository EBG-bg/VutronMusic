import { shallowRef } from 'vue'

export type ActionChoice = 'confirm' | 'secondary' | 'cancel'
export interface ActionQuestion { title: string; message: string; confirmLabel: string; cancelLabel: string; secondaryLabel?: string; secondaryDisabled?: boolean }
type Request = ActionQuestion & { resolve(choice: ActionChoice): void }
export const actionDialog = shallowRef<Request>()
const pending: Request[] = []
export function askAction(question: ActionQuestion): Promise<ActionChoice> {
  return new Promise(resolve => {
    const request = { ...question, resolve }
    if (actionDialog.value) pending.push(request)
    else actionDialog.value = request
  })
}
export function settleAction(choice: ActionChoice) {
  const current = actionDialog.value
  if (!current || choice === 'secondary' && current.secondaryDisabled) return
  actionDialog.value = pending.shift()
  current.resolve(choice)
}
