import request from '@/utils/request'

// 查询领导公务活动列表
export function esQuerys(query) {
  return request({
    url: '/es/querys',
    method: 'post',
    data: query
  })
}

// 热门搜索词（搜索框建议下拉用）
export function hotWords(query) {
  return request({
    url: '/es/hotwords',
    method: 'get',
    params: query
  })
}

// 查询领导公务活动列表
export function getProcessVariablesEsSearch(taskId, isEsSearch) {
  return request({
    url: '/flwDetail/processVariables/' + taskId + '/' + isEsSearch,
    method: 'get'
  })
}
