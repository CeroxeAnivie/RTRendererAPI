#pragma once
#include <DirectXMath.h>
#include <array>
#include <cmath>
#include <stdexcept>

namespace rtr::upstream {
// SceneGraph exposes TRS, while the public API accepts an invertible affine
// matrix. A second rotation node supplies the missing shear degrees of freedom.
// This only adapts input: original graph refresh, motion, buffers and TLAS own
// the composed transform. No official object/private field is overwritten.
struct AffinePose {
    dm::double3 translation{}, scaling{1.};
    dm::dquat rotation=dm::dquat::identity(), inner=dm::dquat::identity();
    bool split=false;

    static dm::dquat Rotation(const dm::double3x3& value) {
        DirectX::XMFLOAT4X4 matrix{};
        for(unsigned r=0;r<3;++r)for(unsigned c=0;c<3;++c)matrix.m[r][c]=float(value[r][c]);
        matrix.m[3][3]=1.f;
        DirectX::XMFLOAT4 q;
        DirectX::XMStoreFloat4(&q,DirectX::XMQuaternionNormalize(
            DirectX::XMQuaternionRotationMatrix(DirectX::XMLoadFloat4x4(&matrix))));
        return dm::dquat::fromXYZW(dm::double3(q.x,q.y,q.z),double(q.w));
    }
    static bool Matches(const dm::daffine3& expected,const AffinePose& pose) {
        const auto actual=pose.inner.toAffine()*dm::scaling(pose.scaling)
            *pose.rotation.toAffine()*dm::translation(pose.translation);
        for(unsigned r=0;r<3;++r)for(unsigned c=0;c<3;++c)
            if(!std::isfinite(actual.m_linear[r][c])||
                std::abs(expected.m_linear[r][c]-actual.m_linear[r][c])>
                    1.e-5*std::max(1.,std::abs(expected.m_linear[r][c])))return false;
        return true;
    }
    static AffinePose Decode(const dm::daffine3& transform) {
        AffinePose pose;auto columnConvention=transform;
        columnConvention.m_linear=dm::transpose(transform.m_linear);
        dm::decomposeAffine(columnConvention,&pose.translation,&pose.rotation,&pose.scaling);
        pose.rotation=dm::conjugate(pose.rotation);
        if(Matches(transform,pose))return pose;
        auto normalized=transform.m_linear;
        for(unsigned r=0;r<3;++r)for(unsigned c=0;c<3;++c)normalized[r][c]/=pose.scaling[r];
        pose.rotation=Rotation(normalized);
        if(Matches(transform,pose))return pose;

        // Donut matrix.h explicitly has no diagonalization/decomposition API.
        // Bounded 3x3 one-sided Jacobi gives L=U*S*V^T. It is only used for
        // matrices a single original TRS cannot represent (normally shear).
        std::array<dm::double3,3> columns, basis;
        for(unsigned c=0;c<3;++c){
            columns[c]=dm::double3(transform.m_linear[0][c],transform.m_linear[1][c],transform.m_linear[2][c]);
            basis[c]=dm::double3(c==0,c==1,c==2);
        }
        for(unsigned sweep=0;sweep<32;++sweep){
            bool changed=false;
            for(unsigned p=0;p<2;++p)for(unsigned q=p+1;q<3;++q){
                const double a=dm::dot(columns[p],columns[p]),b=dm::dot(columns[q],columns[q]);
                const double g=dm::dot(columns[p],columns[q]);
                if(std::abs(g)<=1.e-14*std::sqrt(a*b))continue;
                const double z=(b-a)/(2*g);
                const double t=std::copysign(1.,z)/(std::abs(z)+std::hypot(1.,z));
                const double c=1/std::sqrt(1+t*t),s=c*t;
                const auto x=columns[p],y=columns[q],v=basis[p],w=basis[q];
                columns[p]=c*x-s*y;columns[q]=s*x+c*y;
                basis[p]=c*v-s*w;basis[q]=s*v+c*w;changed=true;
            }
            if(!changed)break;
        }
        for(unsigned c=0;c<3;++c){
            pose.scaling[c]=dm::length(columns[c]);
            if(!(pose.scaling[c]>0)||!std::isfinite(pose.scaling[c]))
                throw std::invalid_argument("Singular affine transform");
            columns[c]/=pose.scaling[c];
        }
        if(dm::dot(dm::cross(columns[0],columns[1]),columns[2])<0){columns[0]=-columns[0];pose.scaling.x=-pose.scaling.x;}
        if(dm::dot(dm::cross(basis[0],basis[1]),basis[2])<0){basis[0]=-basis[0];pose.scaling.x=-pose.scaling.x;}
        dm::double3x3 left,right;
        for(unsigned r=0;r<3;++r)for(unsigned c=0;c<3;++c){left[r][c]=columns[c][r];right[r][c]=basis[r][c];}
        pose.translation=transform.m_translation;pose.inner=Rotation(left);pose.rotation=Rotation(right);pose.split=true;
        if(!Matches(transform,pose))throw std::invalid_argument("Affine node composition failed reconstruction");
        return pose;
    }
};
}
