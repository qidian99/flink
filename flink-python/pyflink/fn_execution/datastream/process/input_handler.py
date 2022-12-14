################################################################################
#  Licensed to the Apache Software Foundation (ASF) under one
#  or more contributor license agreements.  See the NOTICE file
#  distributed with this work for additional information
#  regarding copyright ownership.  The ASF licenses this file
#  to you under the Apache License, Version 2.0 (the
#  "License"); you may not use this file except in compliance
#  with the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
# limitations under the License.
################################################################################
from abc import ABC
from enum import Enum
from typing import cast, Iterable

from pyflink.common import Row
from pyflink.common.constants import DEFAULT_OUTPUT_TAG
from pyflink.datastream.output_tag import OutputTag
from pyflink.fn_execution.datastream.process.timerservice_impl import InternalTimerServiceImpl


class TimerType(Enum):
    EVENT_TIME = 0
    PROCESSING_TIME = 1


class RunnerInputHandler(ABC):
    """
    Handler which handles normal input data.
    """

    def __init__(
        self,
        internal_timer_service: InternalTimerServiceImpl,
        process_element_func,
        has_side_output: bool
    ):
        self._internal_timer_service = internal_timer_service
        self._process_element_func = process_element_func
        self._has_side_output = has_side_output

    def process_element(self, value) -> Iterable:
        timestamp = value[0]
        watermark = value[1]
        data = value[2]
        self._advance_watermark(watermark)
        yield from _emit_results(timestamp,
                                 watermark,
                                 self._process_element_func(data, timestamp),
                                 self._has_side_output)

    def _advance_watermark(self, watermark: int) -> None:
        self._internal_timer_service.advance_watermark(watermark)


class TimerHandler(ABC):
    """
    Handler which handles normal input data.
    """

    def __init__(
        self,
        internal_timer_service: InternalTimerServiceImpl,
        on_event_time_func,
        on_processing_time_func,
        namespace_coder,
        has_side_output
    ):
        self._internal_timer_service = internal_timer_service
        self._on_event_time_func = on_event_time_func
        self._on_processing_time_func = on_processing_time_func
        self._namespace_coder = namespace_coder
        self._has_side_output = has_side_output

    def process_timer(self, timer_data) -> Iterable:
        timer_type = timer_data[0]
        watermark = timer_data[1]
        timestamp = timer_data[2]
        key = timer_data[3]
        serialized_namespace = timer_data[4]

        self._advance_watermark(watermark)
        if self._namespace_coder is not None:
            namespace = self._namespace_coder.decode(serialized_namespace)
        else:
            namespace = None
        if timer_type == TimerType.EVENT_TIME.value:
            yield from _emit_results(
                timestamp,
                watermark,
                self._on_event_time(timestamp, key, namespace),
                self._has_side_output
            )
        elif timer_type == TimerType.PROCESSING_TIME.value:
            yield from _emit_results(
                timestamp,
                watermark,
                self._on_processing_time(timestamp, key, namespace),
                self._has_side_output
            )
        else:
            raise Exception("Unsupported timer type: %d" % timer_type)

    def _on_event_time(self, timestamp, key, namespace) -> Iterable:
        yield from self._on_event_time_func(timestamp, key, namespace)

    def _on_processing_time(self, timestamp, key, namespace) -> Iterable:
        yield from self._on_processing_time_func(timestamp, key, namespace)

    def _advance_watermark(self, watermark: int) -> None:
        self._internal_timer_service.advance_watermark(watermark)


def _emit_results(timestamp, watermark, results, has_side_output):
    if results:
        if has_side_output:
            for result in results:
                if isinstance(result, tuple) and isinstance(result[0], OutputTag):
                    yield cast(OutputTag, result[0]).tag_id, Row(
                        timestamp, watermark, result[1]
                    )
                else:
                    yield DEFAULT_OUTPUT_TAG, Row(timestamp, watermark, result)
        else:
            for result in results:
                yield Row(timestamp, watermark, result)
