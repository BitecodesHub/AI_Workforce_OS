// @find: demo voice, plain voice, technical voice, DemoVoice
// @what: Type naming who a demo speaks to.
// @flow: Used by the demos and DemoStage
/*
 * Who a demo is speaking to. The home page is read by people choosing a product, so its demos use
 * the plain voice: no tool names, no permission codes. The page for IT teams uses the technical
 * voice, which names exactly what the platform checks and logs.
 */
export type DemoVoice = 'plain' | 'technical'
