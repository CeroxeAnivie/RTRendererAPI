# Streaming source models

`RtxptStreamingWorkload --source-model physical-sum` remains the default. It
preserves the original point sources, positions, updates, all-dynamic layer,
inverse-square finite attenuation, physical sum, intensity `.35`, and range `6`.
Existing result files therefore remain physical-sum evidence; they must not be
relabeled as a channel-maximum workload.

`--source-model finite-channel-max` exercises the independent finite-field public
contract: a BOX with half extents `(.5,.5,.5)`, Manhattan-linear attenuation,
`VISIBLE_GROUP_CHANNEL_MAX`, group `0`, and shadows enabled. Range stays `6`.
The authored level is consequently `6`, and its explicit level-to-energy mapping
is `intensity = level / 15 = .4`. The RGB `(.7,.5,.3)` and source positions are
unchanged. This is a different declared light field, not a relabeling of the
physical-sum image or a claim that the two images are equivalent.

Static sources use `CENTER_BOX_SUPPORT_V1`. Dynamic sources retain the same
half-unit emitting box for attenuation and use `CENTER_SPHERE_SUPPORT_V1` with
an independent `.12` visibility radius. The unit-box/level mapping and `.12`
radius are the existing finite-field admission values, not inferred from the
point source's `.35` intensity. The latter is energy and was never a radius.
This fixture selects one supported dynamic radius; it does not claim to cover
every particle, layer, volume, or other producer's radius.

`--static-sources N` is accepted only for `finite-channel-max`, with `0 <= N <=
sources`. The first `N` stable source identities are static and the rest dynamic.
Its default is `max(0, sources - 150)`: the authored dynamic count uses the
fixture's pre-existing update-batch size, not a measured host distribution. Both
static and dynamic source identities can be updated by that unchanged rotating
150-source schedule. Layer identifies baseline participation, not immutable
positions. The source total, spatial layout, geometry, resident roles, flow,
frame schedule, output dimensions, quality, and fresh-output identity gates are
not reduced by selecting this model.

The fixture already submits `HOST_RESPONSE_RATIO_V1`, one direct-gain signal,
and an initialized `NativeLightingHostResponse`. Both models use the same
authored response `.06 + .7*sky/15 + .24*local/15` and terrain baseline `(.15,.8)`.
No additional API input is missing. This stable response lets the source-model
comparison isolate declared field work; it is not a captured display/lightmap
table from any particular host. Exact host-image validation still requires the
host's own response publication and composition.

The selected model and complete field contract appear in `result.json` and the
optional `raw-manifest.json`. `formal_default_specification` remains true only
for the unchanged original physical-sum specification. Compare old/new runtimes
with identical explicit source-model arguments and class bytes; never combine
the two models into a speedup ratio. Region/channel executor availability is a
runtime measurement, not a promised consequence or a substitute for fresh
output, correctness, or actual host frame-rate evidence.

This change only adds a workload model. It does not change production rendering,
upstream code, published artifacts, or any recorded performance result.
