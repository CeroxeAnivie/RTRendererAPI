// RTRendererAPI wire types for the pinned LuxCore priority functions.
// The namespace and minimal material projection do not replace the scene material ABI.
#define OPENCL_PATHVOLUMEINFO_SIZE 32
#define NULL_INDEX 0xffffffffu
#define MATERIALS_PARAM_DECL , const Material *mats
namespace rtr_medium {
struct VolumePriority { int priority; };
struct Material { VolumePriority volume; };
struct __align__(16) PathVolumeInfo {
    unsigned int currentVolumeIndex;
    unsigned int volumeIndexList[OPENCL_PATHVOLUMEINFO_SIZE];
    unsigned int volumeIndexListSize;
    unsigned int scatteredStart;
};
static_assert(sizeof(PathVolumeInfo) == 144, "native medium stack ABI");
