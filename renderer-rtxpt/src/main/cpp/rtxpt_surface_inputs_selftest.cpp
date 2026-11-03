#define RTR_SURFACE_INPUT_PROTOCOL_ONLY
#include "rtxpt_surface_inputs.h"
#include <cstdio>
#include <type_traits>

using namespace RtrSurfaceInputProtocol;
namespace {
unsigned checks=0;
void Check(bool value,const char* name){++checks;if(!value)throw std::runtime_error(name);}
template<class F> void Reject(F operation,const char* name){
    ++checks;try{operation();}catch(const std::exception&){return;}throw std::runtime_error(name);
}
Identity Frame(uint64_t sequence=1){return {sequence,7,11,0xfedcba9876543210ull,-100,16};}
void Matched(const Reservation& slot,const Identity& frame){
    Check(slot.identity==frame,"Complete frame identity was not retained");
    Check(slot.previousDone==slot.lastDone&&slot.ready==slot.previousDone+1&&slot.done==slot.previousDone+2,"Incorrect ready/done timeline");
}
}
int main(){try{
    Check(RequiredPlaneCount(1)==8&&BytesPerPixel(1)==80,"Split input layout must remain eight planes and 80 bytes per pixel");
    Check(RequiredPlaneCount(0)==10&&BytesPerPixel(0)==96,"Augmentation input layout requires two additional RGBA16F planes");
    Reject([]{RequiredPlaneCount(2);},"Unknown host-surface composition accepted");
    Reservation slot;auto first=Frame();slot.Acquire(first);const auto ticket=slot.ticket;Matched(slot,first);
    Reject([&]{slot.Acquire(first);},"Acquired an owned slot");
    Reject([&]{slot.Publish(ticket);},"Published without an import");
    Reject([&]{slot.Begin(ticket,first);},"Read an unpublished input");
    Reject([&]{slot.Imported(ticket+1);},"Accepted a stale reservation ticket");
    slot.CancelUnimported(ticket);Check(slot.state==State::Idle&&slot.lastDone==0,"Unimported cancellation advanced an unsignaled fence");
    slot.Acquire(first);Check(slot.ticket==ticket+1&&slot.ready==1&&slot.done==2,"Unimported retry did not reuse the safe timeline with a new ticket");
    Reject([&]{slot.Imported(ticket);},"Old ticket imported a replacement reservation");
    const auto retry=slot.ticket;slot.Imported(retry);
    Reject([&]{slot.Imported(retry);},"Duplicate import was accepted");
    Reject([&]{slot.CancelUnimported(retry);},"Imported writes cancelled without a host-ready fence");
    slot.Publish(retry);
    Reject([&]{slot.Publish(retry);},"Duplicate publication was accepted");
    for(unsigned fact=0;fact<6;fact++){
        auto wrong=first;switch(fact){case 0:++wrong.sequence;break;case 1:++wrong.revision;break;case 2:++wrong.resourceGeneration;break;
            case 3:++wrong.viewIdentity;break;case 4:++wrong.timeNanos;break;case 5:++wrong.deltaNanos;break;}
        Reject([&]{slot.Begin(retry,wrong);},"Mismatched frame facts were accepted");
        Check(slot.state==State::Published,"Rejected metadata consumed the publication");
    }
    slot.Begin(retry,first);slot.Abort(retry);Check(slot.state==State::Published,"Pre-submit abort did not preserve a retryable publication");
    slot.Begin(retry,first);Reject([&]{slot.DiscardQueued(retry);},"Discarded an actively read input");
    slot.Finish(retry);Check(slot.state==State::InFlight&&slot.lastDone==2,"Read completion did not retain in-flight ownership");
    Reject([&]{slot.Acquire(Frame(2));},"Reused a GPU-owned input");
    slot.Complete(1);Check(slot.state==State::InFlight,"Retired before rendererDone");
    slot.Complete(2);Check(slot.state==State::Idle,"Completed input did not retire");
    Reject([&]{slot.Finish(retry);},"Duplicate read completion was accepted");
    slot.Acquire(Frame(2));Matched(slot,Frame(2));const auto abandoned=slot.ticket;slot.Imported(abandoned);slot.DiscardQueued(abandoned);
    Check(slot.state==State::InFlight&&slot.lastDone==4,"Imported abandonment was not retained until GPU completion");
    slot.Complete(3);Check(slot.state==State::InFlight,"Imported abandonment ignored host/renderer ordering");
    slot.Complete(4);slot.Acquire(Frame(3));slot.Imported(slot.ticket);slot.Publish(slot.ticket);slot.DiscardQueued(slot.ticket);slot.Complete(6);
    Check(slot.state==State::Idle&&slot.lastDone==6,"Published discard did not complete on the same timeline");
    auto invalid=Frame();invalid.sequence=uint64_t(std::numeric_limits<int64_t>::max())+1;
    Reject([&]{slot.Acquire(invalid);},"Accepted sequence outside the Java ABI");
    invalid=Frame();invalid.deltaNanos=-1;Reject([&]{slot.Acquire(invalid);},"Accepted negative clock delta");
    slot.lastDone=uint64_t(std::numeric_limits<int64_t>::max())-1;
    Reject([&]{slot.Acquire(Frame());},"Fence timeline overflow was accepted");
    slot.lastDone=0;slot.ticket=uint64_t(std::numeric_limits<int64_t>::max());
    Reject([&]{slot.Acquire(Frame());},"Reservation ticket overflow was accepted");
    std::printf("RTXPT_SURFACE_INPUT_PROTOCOL_SELFTEST PASS checks=%u staleTickets=rejected retry=preserved gpuRetirement=ordered\n",checks);return 0;
}catch(const std::exception& failure){std::fprintf(stderr,"Host-surface protocol selftest failed: %s\n",failure.what());return 1;}}
