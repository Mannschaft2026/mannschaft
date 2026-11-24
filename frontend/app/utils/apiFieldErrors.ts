/** API の 400 応答に含まれる fieldErrors（field・message）を取り出す。無ければ空配列。 */
export function extractApiFieldErrors(e: unknown): Array<{ field: string; message: string }> {
  const x = e as { data?: { error?: { fieldErrors?: unknown } }; response?: { _data?: { error?: { fieldErrors?: unknown } } } }
  const raw = x?.data?.error?.fieldErrors ?? x?.response?._data?.error?.fieldErrors
  if (!Array.isArray(raw)) return []
  return raw
    .filter((fe): fe is { field: string; message: string } => typeof fe?.field === 'string' && typeof fe?.message === 'string')
    .map((fe) => ({ field: fe.field, message: fe.message }))
}
