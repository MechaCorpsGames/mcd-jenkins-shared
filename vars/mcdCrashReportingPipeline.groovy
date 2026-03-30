// Deprecated: Use mcdBackendServicesPipeline instead.
// This file exists for backward compatibility with existing Jenkins job configs.
def call(Map config) {
    mcdBackendServicesPipeline(config)
}

return this
