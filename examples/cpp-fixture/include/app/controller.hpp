#pragma once

#include "model/node.hpp"
#include "store/node.hpp"

namespace app {
class Controller : public model::Node {
public:
  int run(int value) const;
  int run(double value) const;
  store::Node resolve(const store::Node& value) const;
};
}
