// Standalone public-SDK measurement. No Java, JNI, or RTRenderer runtime calls.
#include <windows.h>
#include <luxcore/luxcore.h>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <memory>
#include <thread>

int main(int argc, char** argv) {
    if (argc < 5) { std::cerr << "usage: luxcore_native_benchmark config sdk-bin engine output [workgroup=32] [seconds=3] [rounds=3]\n"; return 2; }
    try {
        const std::filesystem::path configPath = std::filesystem::absolute(argv[1]);
        const std::filesystem::path sdkBin = std::filesystem::absolute(argv[2]);
        for (const auto name : {L"nvrtc-builtins64_128.dll",L"nvrtc64_120_0.dll",L"tbb12.dll",L"tbbmalloc.dll",L"embree4.dll",L"LuxOpenImageDenoise_core.dll",L"LuxOpenImageDenoise.dll",L"luxcore.dll"}) {
            if (!LoadLibraryExW((sdkBin/name).c_str(), nullptr, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR|LOAD_LIBRARY_SEARCH_DEFAULT_DIRS))
                throw std::runtime_error("cannot load SDK dependency, Windows error="+std::to_string(GetLastError()));
        }
        luxcore::Init();
        luxcore::AddFileNameResolverPath(configPath.parent_path().generic_string());
        auto properties = std::make_unique<luxrays::Properties>(configPath.string());
        const std::string engine=argv[3];
        const unsigned int workgroup=argc>5?std::stoul(argv[5]):32;
        const double seconds=argc>6?std::stod(argv[6]):3;
        const int rounds=argc>7?std::stoi(argv[7]):3;
        properties->Set(luxrays::Property("renderengine.type")(engine));
        properties->Set(luxrays::Property("sampler.type")(engine=="PATHOCL"?"SOBOL":"TILEPATHSAMPLER"));
        properties->Set(luxrays::Property("opencl.gpu.workgroup.size")(workgroup));
        auto config=luxcore::RenderConfig::Create(std::move(properties));
        auto session=luxcore::RenderSession::Create(config);
        session->Start();
        const double pixels=session->GetFilm().GetWidth()*double(session->GetFilm().GetHeight());
        // Start() and all first-time compilation are outside the measurement.
        std::this_thread::sleep_for(std::chrono::seconds(2));
        auto samples=[&] { session->UpdateStats();return session->GetStats()->Get("stats.renderengine.total.samplecount").Get<double>(); };
        std::ofstream output(argv[4]);output<<std::setprecision(12);
        output<<"{\"engine\":\""<<engine<<"\",\"workgroup\":"<<workgroup<<",\"pixels\":"<<pixels<<",\"rounds\":[";
        for(int round=1;round<=rounds;++round) {
            const double before=samples();const auto start=std::chrono::steady_clock::now();
            std::this_thread::sleep_for(std::chrono::duration<double>(seconds));
            const double after=samples();const auto end=std::chrono::steady_clock::now();
            const double wall=std::chrono::duration<double>(end-start).count();
            const double fps=(after-before)/pixels/wall;
            if (round>1)output<<',';
            output<<"{\"round\":"<<round<<",\"wallMs\":"<<wall*1000<<",\"sampleDelta\":"<<after-before<<",\"fps\":"<<fps<<",\"ms\":"<<(fps>0?1000/fps:0)<<'}';
            std::cout<<"NativeSdk engine="<<engine<<" workgroup="<<workgroup<<" round="<<round<<" fps="<<fps<<" ms="<<(fps>0?1000/fps:0)<<std::endl;
        }
        output<<"]}\n";output.close();
        std::ofstream stats(std::string(argv[4])+".stats.txt");stats<<session->GetStats()->ToString();stats.close();
        session->Stop();return 0;
    } catch (const std::exception& error) {std::cerr<<"NativeSdk error: "<<error.what()<<std::endl;return 1;}
}
