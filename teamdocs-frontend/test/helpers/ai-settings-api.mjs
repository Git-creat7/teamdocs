export const handlers = {}
export const getMyModelApi = (...args) => handlers.model(...args)
export const saveMyModelApi = (...args) => handlers.save(...args)
export const testMyModelApi = (...args) => handlers.test(...args)
export const disableMyModelApi = (...args) => handlers.disable(...args)
export const getAiDependenciesApi = (...args) => handlers.dependencies(...args)
export const testAiDependencyApi = (...args) => handlers.probe(...args)
