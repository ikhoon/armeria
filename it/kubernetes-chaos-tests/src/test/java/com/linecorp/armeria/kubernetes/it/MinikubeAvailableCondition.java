/*
 * Copyright 2023 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.kubernetes.it;

import java.io.BufferedReader;
import java.io.InputStreamReader;

final class MinikubeAvailableCondition {

    static boolean isMinikubeRunning() {
        try {
            final ProcessBuilder pb = new ProcessBuilder("minikube", "status");
            final Process p = pb.start();

            String line;
            final BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            while ((line = reader.readLine()) != null) {
                if (line.toLowerCase().startsWith("host: running")) {
                    return true;
                }
            }
        } catch (Exception ignore) {
        }
        return false;
    }

    private MinikubeAvailableCondition() {}
}
