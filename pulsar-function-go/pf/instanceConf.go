//
// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
//

package pf

import (
	"encoding/json"
	"fmt"
	"time"

	"github.com/apache/pulsar/pulsar-function-go/conf"
	pb "github.com/apache/pulsar/pulsar-function-go/pb"
)

// This is the config passed to the Golang Instance. Contains all the information
// passed to run functions
type instanceConf struct {
	instanceID                  int
	funcID                      string
	funcVersion                 string
	funcDetails                 pb.FunctionDetails
	maxBufTuples                int
	port                        int
	clusterName                 string
	pulsarServiceURL            string
	killAfterIdle               time.Duration
	expectedHealthCheckInterval int32
	metricsPort                 int
	authPlugin                  string
	authParams                  string
	tlsTrustCertsPath           string
	tlsAllowInsecure            bool
	tlsHostnameVerification     bool
}

func newInstanceConfWithConf(cfg *conf.Conf) *instanceConf {
	inputSpecs := make(map[string]*pb.ConsumerSpec)
	// for backward compatibility
	if cfg.SourceSpecTopic != "" {
		inputSpecs[cfg.SourceSpecTopic] = &pb.ConsumerSpec{
			SchemaType:     cfg.SourceSchemaType,
			IsRegexPattern: cfg.IsRegexPatternSubscription,
			ReceiverQueueSize: &pb.ConsumerSpec_ReceiverQueueSize{
				Value: cfg.ReceiverQueueSize,
			},
		}
	}
	for topic, value := range cfg.SourceInputSpecs {
		spec := &pb.ConsumerSpec{}
		if err := json.Unmarshal([]byte(value), spec); err != nil {
			panic(fmt.Sprintf("Failed to unmarshal consume specs: %v", err))
		}
		inputSpecs[topic] = spec
	}
	instanceConf := &instanceConf{
		instanceID:                  cfg.InstanceID,
		funcID:                      cfg.FuncID,
		funcVersion:                 cfg.FuncVersion,
		maxBufTuples:                cfg.MaxBufTuples,
		port:                        cfg.Port,
		clusterName:                 cfg.ClusterName,
		pulsarServiceURL:            cfg.PulsarServiceURL,
		killAfterIdle:               cfg.KillAfterIdleMs,
		expectedHealthCheckInterval: cfg.ExpectedHealthCheckInterval,
		metricsPort:                 cfg.MetricsPort,
		funcDetails: pb.FunctionDetails{
			Tenant:               cfg.Tenant,
			Namespace:            cfg.NameSpace,
			Name:                 cfg.Name,
			LogTopic:             cfg.LogTopic,
			ProcessingGuarantees: pb.ProcessingGuarantees(cfg.ProcessingGuarantees),
			SecretsMap:           cfg.SecretsMap,
			Runtime:              pb.FunctionDetails_Runtime(cfg.Runtime),
			AutoAck:              cfg.AutoACK,
			Parallelism:          cfg.Parallelism,
			Source: &pb.SourceSpec{
				SubscriptionType:     pb.SubscriptionType(cfg.SubscriptionType),
				InputSpecs:           inputSpecs,
				TimeoutMs:            cfg.TimeoutMs,
				SubscriptionName:     cfg.SubscriptionName,
				CleanupSubscription:  cfg.CleanupSubscription,
				SubscriptionPosition: pb.SubscriptionPosition(cfg.SubscriptionPosition),
			},
			Sink: &pb.SinkSpec{
				Topic:      cfg.SinkSpecTopic,
				SchemaType: cfg.SinkSchemaType,
			},
			Resources: &pb.Resources{
				Cpu:  cfg.Cpu,
				Ram:  cfg.Ram,
				Disk: cfg.Disk,
			},
			RetryDetails: &pb.RetryDetails{
				MaxMessageRetries: cfg.MaxMessageRetries,
				DeadLetterTopic:   cfg.DeadLetterTopic,
			},
			UserConfig: cfg.UserConfig,
		},
		authPlugin:              cfg.ClientAuthenticationPlugin,
		authParams:              cfg.ClientAuthenticationParameters,
		tlsTrustCertsPath:       cfg.TLSTrustCertsFilePath,
		tlsAllowInsecure:        cfg.TLSAllowInsecureConnection,
		tlsHostnameVerification: cfg.TLSHostnameVerificationEnable,
	}

	if instanceConf.funcDetails.ProcessingGuarantees == pb.ProcessingGuarantees_EFFECTIVELY_ONCE {
		panic("Go instance current not support EFFECTIVELY_ONCE processing guarantees.")
	}

	if !instanceConf.funcDetails.AutoAck &&
		(instanceConf.funcDetails.ProcessingGuarantees == pb.ProcessingGuarantees_ATMOST_ONCE ||
			instanceConf.funcDetails.ProcessingGuarantees == pb.ProcessingGuarantees_ATLEAST_ONCE) {
		panic("When Guarantees == " + instanceConf.funcDetails.ProcessingGuarantees.String() +
			", autoAck must be equal to true. If you want not to automatically ack, " +
			"please configure the processing guarantees as MANUAL." +
			" This is a contradictory configuration, autoAck will be removed later." +
			" Please refer to PIP: https://github.com/apache/pulsar/issues/15560")
	}

	return instanceConf
}

func newInstanceConf() *instanceConf {
	config := &conf.Conf{}
	cfg := config.GetConf()
	if cfg == nil {
		panic("config file is nil.")
	}
	return newInstanceConfWithConf(cfg)
}

func (ic *instanceConf) getInstanceName() string {
	return "" + fmt.Sprintf("%d", ic.instanceID)
}
