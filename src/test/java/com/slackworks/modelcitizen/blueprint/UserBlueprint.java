package com.slackworks.modelcitizen.blueprint;

/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
   *
 * http://www.apache.org/licenses/LICENSE-2.0
   *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.slackworks.modelcitizen.annotation.Blueprint;
import com.slackworks.modelcitizen.annotation.Default;
import com.slackworks.modelcitizen.field.FieldCallBack;
import com.slackworks.modelcitizen.model.User;

@Blueprint(User.class )
public class UserBlueprint {
	
	@Default
	public FieldCallBack username = new FieldCallBack() {

		@Override
		public String get( Object model) {
			return "username" + UUID.randomUUID();
		}
		
	};
	
	@Default
	public FieldCallBack emails = new FieldCallBack() {

		@Override
		public List<String> get( Object model) {
			List<String> emails = new ArrayList();
			emails.add( "email" + UUID.randomUUID() + "@test.net" );
			emails.add( "email" + UUID.randomUUID() + "@test.net" );
			emails.add( "email" + UUID.randomUUID() + "@test.net" );
			
			return emails;
		}
		
	};
}
