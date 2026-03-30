// Deprecated: Use mcdServicesPipeline instead.
// This file exists for backward compatibility with existing Jenkins job configs.
def call(Map config) {
    mcdServicesPipeline(config)
}

return this
