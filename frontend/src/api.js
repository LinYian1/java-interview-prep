async function request(method, url, body) {
  const opt = { method, headers: {} }
  if (body !== undefined) {
    opt.headers['Content-Type'] = 'application/json'
    opt.body = JSON.stringify(body)
  }
  const resp = await fetch(url, opt)
  let data = null
  try {
    data = await resp.json()
  } catch {
    // 非 JSON 响应（如 500 空体）
  }
  if (!resp.ok) {
    throw new Error((data && data.message) || `请求失败 (${resp.status})`)
  }
  return data
}

export const api = {
  get: (url) => request('GET', url),
  post: (url, body) => request('POST', url, body ?? {}),
  put: (url, body) => request('PUT', url, body)
}
