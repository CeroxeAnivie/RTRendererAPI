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
#include <vector>
#include <cmath>

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
        const bool completeOutput=argc>8 && std::string(argv[8])=="--complete-output";
        properties->Set(luxrays::Property("renderengine.type")(engine));
        properties->Set(luxrays::Property("sampler.type")(engine=="PATHOCL"?"SOBOL":"TILEPATHSAMPLER"));
        properties->Set(luxrays::Property("opencl.gpu.workgroup.size")(workgroup));
        if(completeOutput) {
            if(engine!="RTPATHOCL")throw std::runtime_error("Complete-frame endpoint requires native RTPATHOCL full-frame tiles");
            // Restore upstream's native realtime defaults (rtpathocl.cpp GetDefaultProps).
            properties->Set(luxrays::Property("rtpath.resolutionreduction")(4));
            properties->Set(luxrays::Property("rtpath.resolutionreduction.preview")(4));
            properties->Set(luxrays::Property("rtpath.resolutionreduction.preview.step")(8));
        }
        auto config=luxcore::RenderConfig::Create(std::move(properties));
        auto session=luxcore::RenderSession::Create(config);
        session->Start();
        const double pixels=session->GetFilm().GetWidth()*double(session->GetFilm().GetHeight());
        // Start() and all first-time compilation are outside the measurement.
        std::this_thread::sleep_for(std::chrono::seconds(2));
        if(completeOutput) {
            auto& film=session->GetFilm();
            std::vector<float> rgba(size_t(pixels)*4);
            auto committedSamples=[&] { return film.GetStats()->Get("stats.film.total.samplecount").Get<double>(); };
            double last=committedSamples();
            std::ofstream out(argv[4]);out<<std::setprecision(12);
            out<<"{\"engine\":\"RTPATHOCL\",\"width\":1920,\"height\":1080,\"resolutionReduction\":4,\"effectiveRayGrid\":[480,270],\"fg\":false,\"frameMs\":[";
            auto start=std::chrono::steady_clock::now();
            double total=0;unsigned frames=0;
            do {
                auto frameStart=std::chrono::steady_clock::now();
                double next;
                // Native RT engine commits one complete image tile at a time.
                // WaitNewFrame only updates counters in 2.11.2, so it is NOT a fence.
                while((next=committedSamples())<=last)std::this_thread::sleep_for(std::chrono::microseconds(200));
                film.GetOutput<float>(luxcore::Film::OUTPUT_RGBA_IMAGEPIPELINE,rgba.data());
                last=committedSamples(); // Never count an image updated during the output copy again.
                const double ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-frameStart).count();
                if(frames++)out<<',';out<<ms;total+=ms;
            }while(std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count()<seconds || frames<12);
            bool visible=false;
            for(float value:rgba){if(!std::isfinite(value))throw std::runtime_error("Invalid film output");visible|=value>0;}
            if(!visible)throw std::runtime_error("Empty film output");
            out<<"],\"frames\":"<<frames<<",\"fps\":"<<frames*1000./total<<",\"meanMs\":"<<total/frames<<",\"metric\":\"Wait for newly committed native full-frame tile plus upstream RGBA image pipeline and CPU output copy; no presentation\"}\n";
            out.close();
            film.SaveOutput(std::string(argv[4])+".png",luxcore::Film::OUTPUT_RGBA_IMAGEPIPELINE,luxrays::Properties());
            session->UpdateStats();std::ofstream stats(std::string(argv[4])+".stats.txt");stats<<session->GetStats()->ToString();
            std::cout<<"LUXCORE COMPLETE RESULT frames="<<frames<<" fps="<<frames*1000./total<<" meanMs="<<total/frames<<std::endl;
            session->Stop();return 0;
        }
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
