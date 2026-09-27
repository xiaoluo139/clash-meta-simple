package tunnel

import (
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/tunnel"
)

// RuleStat mirrors the information mihomo keeps for every loaded routing rule.
// The hit / miss counters are maintained by mihomo's own RuleWrapper, so we
// only have to read them out here.
type RuleStat struct {
	Index     int    `json:"index"`
	Type      string `json:"type"`
	Payload   string `json:"payload"`
	Proxy     string `json:"proxy"`
	Disabled  bool   `json:"disabled"`
	HitCount  uint64 `json:"hitCount"`
	MissCount uint64 `json:"missCount"`
}

func QueryRules() []*RuleStat {
	rawRules := tunnel.Rules()

	rules := make([]*RuleStat, 0, len(rawRules))

	for index, rule := range rawRules {
		stat := &RuleStat{
			Index:   index,
			Type:    rule.RuleType().String(),
			Payload: rule.Payload(),
			Proxy:   rule.Adapter(),
		}

		if wrapper, ok := rule.(C.RuleWrapper); ok {
			stat.Disabled = wrapper.IsDisabled()
			stat.HitCount = wrapper.HitCount()
			stat.MissCount = wrapper.MissCount()
		}

		rules = append(rules, stat)
	}

	return rules
}